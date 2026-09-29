package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.CoreConfig
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeOptions
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Read-only evidence for the dirty-tree synchronization path; never stores file contents. */
internal object SynchronizationSafety {
    data class IndexEntry(
        val path: String,
        val stage: Int,
        val objectId: String,
        val mode: Int,
        val assumeValid: Boolean,
        val skipWorkTree: Boolean,
        val intentToAdd: Boolean,
    )

    data class FileFingerprint(val kind: String, val digest: String?, val executable: Boolean = false)

    data class LocalWork(
        val changes: WorkingTreeChanges,
        val affectedPaths: Set<String>,
        val ignoredPaths: Set<String>,
        val files: Map<String, FileFingerprint>,
        val index: List<IndexEntry>,
    )

    private data class TreeEntry(val objectId: ObjectId, val mode: Int)

    fun affectedRemotePaths(repository: Repository, head: ObjectId, remote: ObjectId): Set<String> =
        RevWalk(repository).use { walk ->
            DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
                diff.setRepository(repository)
                // Without rename folding, both the deletion and addition remain visible.
                diff.scan(walk.parseCommit(head).tree, walk.parseCommit(remote).tree)
                    .flatMap { listOf(it.oldPath, it.newPath) }
                    .filter { it != DiffEntry.DEV_NULL }
                    .toSortedSet()
            }
        }

    fun captureLocalWork(git: Git, changes: WorkingTreeChanges): LocalWork {
        val repository = git.repository
        val root = repository.workTree.canonicalFile.toPath()
        val index = readIndex(repository)
        check(index.none { it.assumeValid || it.skipWorkTree || it.intentToAdd ||
            it.mode == FileMode.GITLINK.bits || it.stage != 0 }) {
            "El índice contiene condiciones cuya preservación no se puede garantizar."
        }
        val workingObjects = readWorkingObjects(repository, index.map { it.path }.toSet())
        val status = git.status().call()
        val localPaths = (changes.newFiles + changes.modifiedFiles + changes.deletedFiles +
            changes.stagedFiles + changes.conflictingFiles).toSortedSet()
        val reportedWorkingChanges = status.modified + status.missing
        check(index.none { entry ->
            entry.path !in reportedWorkingChanges &&
                (workingObjects[entry.path]?.objectId?.name != entry.objectId ||
                    !compatibleMode(repository, entry.mode, workingObjects[entry.path]?.mode))
        }) {
            // The existing upload contract can also trust stat metadata when staging. Do not
            // proceed if it could omit work that our independent content scan discovered.
            "El contenido local no coincide con el estado de cambios comunicado por Git."
        }
        val ignored = status.ignoredNotInIndex.flatMap { ignoredPath ->
            val path = checkedPath(root, ignoredPath)
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                listOf(ignoredPath)
            } else {
                // JGit can report a whole ignored directory. Inspect its existing leaves.
                Files.walk(path).use { entries ->
                    entries.filter { entry ->
                        !Files.isDirectory(entry, NOFOLLOW_LINKS) ||
                            Files.list(entry).use { !it.findAny().isPresent }
                    }.map { root.relativize(it).toString().replace('\\', '/') }
                        .iterator().asSequence().toList()
                }
            }
        }.toSortedSet()
        // Also hash clean tracked files: changes with unchanged stat metadata must not escape the guard.
        val files = (localPaths + ignored + index.map { it.path }).associateWith {
            fingerprint(checkedPath(root, it))
        }
        return LocalWork(changes, localPaths, ignored, files, index)
    }

    fun hasCollision(local: LocalWork, remotePaths: Set<String>): Boolean {
        val protected = (local.affectedPaths + local.ignoredPaths).map(::collisionKey)
        val remote = remotePaths.map(::collisionKey)
        return protected.any { first -> remote.any { second -> pathsCollide(first, second) } }
    }

    fun verifyCheckoutIsSafe(repository: Repository, head: ObjectId, remote: ObjectId,
                             remotePaths: Set<String>) {
        val root = repository.workTree.canonicalFile.toPath()
        remotePaths.forEach { checkedPath(root, it) }
        // These files can indirectly change the meaning/staging of otherwise disjoint local paths.
        check(remotePaths.none { it.substringAfterLast('/').lowercase(Locale.ROOT) in
            setOf(".gitignore", ".gitattributes", ".gitmodules") }) {
            "La actualización cambia reglas Git que podrían afectar al trabajo local."
        }
        val target = readTree(repository, remote)
        check(target.values.none { it.mode == FileMode.GITLINK.bits }) {
            "No se puede verificar automáticamente el contenido de los submódulos."
        }
        val targetKeys = target.keys.map(::collisionKey)
        val distinctKeys = targetKeys.toSet()
        check(distinctKeys.size == targetKeys.size && targetKeys.none { path ->
            generateSequence(path.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
                .takeWhile { it.isNotEmpty() }.any { it in distinctKeys }
        }) {
            "El árbol remoto contiene rutas que pueden representar el mismo archivo."
        }
        RevWalk(repository).use { walk ->
            val checkout = DirCacheCheckout(repository, walk.parseCommit(head).tree,
                DirCache.read(repository), walk.parseCommit(remote).tree)
            checkout.setFailOnConflict(true)
            checkout.preScanTwoTrees()
            check(checkout.conflicts.isEmpty()) { "JGit detectó una condición de checkout insegura."
            }
        }
    }

    fun verifyLocalWorkPreserved(before: LocalWork, after: LocalWork, remotePaths: Set<String>) {
        check(after.changes == before.changes && after.ignoredPaths == before.ignoredPaths) {
            "La clasificación del trabajo local cambió durante la descarga."
        }
        val remoteKeys = remotePaths.map(::collisionKey)
        fun affectedByRemote(path: String) = remoteKeys.any { pathsCollide(collisionKey(path), it) }
        check(before.files.filterKeys { !affectedByRemote(it) } ==
            after.files.filterKeys { !affectedByRemote(it) }) {
            "El contenido del trabajo local cambió durante la descarga."
        }
        check(before.index.filter { !affectedByRemote(it.path) } ==
            after.index.filter { !affectedByRemote(it.path) }) {
            "El índice local cambió durante la descarga."
        }
    }

    fun verifyRemoteFilesApplied(repository: Repository, remote: ObjectId, remotePaths: Set<String>) {
        val tree = readTree(repository, remote)
        val index = readIndex(repository).associateBy { it.path }
        val root = repository.workTree.canonicalFile.toPath()
        val workingObjects = readWorkingObjects(repository, remotePaths.filter { tree[it] != null }.toSet())
        remotePaths.forEach { path ->
            val expected = tree[path]
            val actual = index[path]
            if (expected == null) {
                check(actual == null) { "Una eliminación remota no se aplicó al índice."
                }
                val file = checkedPath(root, path)
                check(!Files.exists(file, NOFOLLOW_LINKS) ||
                    Files.isDirectory(file, NOFOLLOW_LINKS) && tree.keys.any { it.startsWith("$path/") }) {
                    "Una eliminación remota no se aplicó al working tree."
                }
            } else {
                check(actual?.objectId == expected.objectId.name && actual.mode == expected.mode) {
                    "El índice no contiene la versión remota esperada."
                }
                check(workingObjects[path]?.objectId == expected.objectId &&
                    compatibleMode(repository, expected.mode, workingObjects[path]?.mode)) {
                    "El contenido remoto no quedó aplicado en el working tree."
                }
            }
        }
    }

    private fun readIndex(repository: Repository): List<IndexEntry> {
        val cache = DirCache.read(repository)
        return (0 until cache.entryCount).map { position ->
            val entry = cache.getEntry(position)
            IndexEntry(entry.pathString, entry.stage, entry.objectId.name, entry.rawMode,
                entry.isAssumeValid, entry.isSkipWorkTree, entry.isIntentToAdd)
        }
    }

    private fun readTree(repository: Repository, commit: ObjectId): Map<String, TreeEntry> =
        RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { walk ->
                walk.addTree(revisions.parseCommit(commit).tree)
                walk.isRecursive = true
                buildMap {
                    while (walk.next()) put(walk.pathString,
                        TreeEntry(walk.getObjectId(0), walk.getRawMode(0)))
                }
            }
        }

    private fun readWorkingObjects(repository: Repository, paths: Set<String>): Map<String, TreeEntry> {
        if (paths.isEmpty()) return emptyMap()
        return RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { walk ->
                walk.addTree(revisions.parseCommit(requireNotNull(repository.resolve("HEAD"))).tree)
                val index = DirCache.read(repository)
                // This independent in-memory cache is never written. Its transient flag makes
                // JGit hash actual bytes while retaining index context for EOL/attribute rules.
                for (position in 0 until index.entryCount) index.getEntry(position).isUpdateNeeded = true
                val indexPosition = walk.addTree(DirCacheIterator(index))
                val files = FileTreeIterator(repository)
                val working = walk.addTree(files)
                files.setDirCacheIterator(walk, indexPosition)
                walk.isRecursive = true
                walk.filter = PathFilterGroup.createFromStrings(paths)
                walk.operationType = TreeWalk.OperationType.CHECKIN_OP
                buildMap {
                    while (walk.next()) if (walk.pathString in paths) {
                        put(walk.pathString, TreeEntry(walk.getObjectId(working), walk.getRawMode(working)))
                    }
                }
            }
        }
    }

    private fun compatibleMode(repository: Repository, expected: Int, actual: Int?): Boolean {
        if (actual == expected) return true
        val options = repository.config.get(WorkingTreeOptions.KEY)
        val files = setOf(FileMode.REGULAR_FILE.bits, FileMode.EXECUTABLE_FILE.bits)
        if (!options.isFileMode && expected in files && actual in files) return true
        return options.symLinks == CoreConfig.SymLinks.FALSE && expected == FileMode.SYMLINK.bits &&
            actual in files
    }

    private fun collisionKey(path: String): String {
        check('\\' !in path) { "La ruta contiene un separador ambiguo."
        }
        val segments = path.split('/')
        check(segments.all { it.isNotEmpty() && it != "." && it != ".." &&
            !it.endsWith('.') && !it.endsWith(' ') && it.none { char -> char.code < 32 || char in ":*?<>|" } &&
            it.lowercase(Locale.ROOT) != ".git" &&
            !it.substringBefore('.').uppercase(Locale.ROOT).matches(
                Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) }) { "La ruta contiene segmentos ambiguos."
        }
        return Normalizer.normalize(segments.joinToString("/").uppercase(Locale.ROOT)
            .lowercase(Locale.ROOT), Normalizer.Form.NFC)
    }

    private fun pathsCollide(first: String, second: String) =
        first == second || first.startsWith("$second/") || second.startsWith("$first/")

    private fun checkedPath(root: Path, path: String): Path {
        collisionKey(path)
        val relative = root.fileSystem.getPath(path)
        check(!relative.isAbsolute && relative.none { it.toString() == "." || it.toString() == ".." }) {
            "La ruta no es relativa al repositorio."
        }
        val resolved = root.resolve(relative).normalize()
        check(resolved.startsWith(root) && resolved != root) { "La ruta sale del repositorio."
        }
        var parent = resolved.parent
        while (parent != null && parent != root) {
            check(!Files.isSymbolicLink(parent)) { "La ruta atraviesa un enlace simbólico."
            }
            parent = parent.parent
        }
        return resolved
    }

    private fun fingerprint(path: Path): FileFingerprint {
        var parent = path.parent
        while (parent != null) {
            if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                return FileFingerprint("missing", null)
            }
            parent = parent.parent
        }
        val before = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return FileFingerprint("missing", null)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val kind = when {
            before.isSymbolicLink -> {
                digest.update(Files.readSymbolicLink(path).toString().toByteArray(Charsets.UTF_8))
                "symlink"
            }
            before.isRegularFile -> {
                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                "file"
            }
            before.isDirectory -> {
                val names = Files.list(path).use { entries ->
                    entries.map { it.fileName.toString() }.sorted().iterator().asSequence().toList()
                }
                names.forEach {
                    digest.update(it.toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                }
                "directory"
            }
            else -> error("No se puede verificar un archivo de este tipo.")
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.fileKey() == after.fileKey()) { "Un archivo cambió mientras se comprobaba."
        }
        return FileFingerprint(kind, digest.digest().joinToString("") { "%02x".format(it) },
            kind == "file" && Files.isExecutable(path))
    }
}
