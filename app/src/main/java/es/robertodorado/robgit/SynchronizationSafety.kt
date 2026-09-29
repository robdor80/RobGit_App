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
        syncMeasure(SyncPhase.REMOTE_PATHS) {
            syncRevWalk(repository) { walk ->
                DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
                    diff.setRepository(repository)
                    // Keep the existing effective rename configuration and both endpoints of each diff entry.
                    val monitor = SyncPerformance.current()?.let { SyncRenameMonitor(it) }
                    if (monitor != null) diff.setProgressMonitor(monitor)
                    syncCount(if (diff.isDetectRenames) SyncCounter.RENAME_ENABLED else SyncCounter.RENAME_DISABLED)
                    syncMeasure(SyncPhase.DIFF_SCAN) {
                        try {
                            diff.scan(walk.parseCommit(head).tree, walk.parseCommit(remote).tree)
                        } finally { monitor?.finish() }
                    }.also { entries ->
                        syncCount(SyncCounter.DIFF_ENTRIES, entries.size.toLong())
                        syncCount(SyncCounter.RENAMES_RETURNED, entries.count {
                            it.changeType == DiffEntry.ChangeType.RENAME || it.changeType == DiffEntry.ChangeType.COPY
                        }.toLong())
                    }.flatMap { listOf(it.oldPath, it.newPath) }
                        .filter { it != DiffEntry.DEV_NULL }
                        .toSortedSet().also { syncCount(SyncCounter.REMOTE_PATHS, it.size.toLong()) }
                }
            }
        }

    fun captureLocalWork(git: Git, changes: WorkingTreeChanges,
                         remotePaths: Set<String> = emptySet()): LocalWork {
        return syncMeasure(SyncPhase.CAPTURE) {
            syncCount(SyncCounter.CAPTURE_CALLS)
            val repository = git.repository
            val root = repository.workTree.canonicalFile.toPath()
            val index = readIndex(repository)
            check(index.none { it.assumeValid || it.skipWorkTree || it.intentToAdd ||
                it.mode == FileMode.GITLINK.bits || it.stage != 0 }) {
                "El índice contiene condiciones cuya preservación no se puede garantizar."
            }
            val localPaths = (changes.newFiles + changes.modifiedFiles + changes.deletedFiles +
                changes.stagedFiles + changes.conflictingFiles).toSortedSet()
            // A: pending local work. B: tracked paths the remote could overwrite, including
            // aliases and file/directory ancestors. C: clean unrelated files trust Git's index
            // and status; they are not independently rehashed. Keep the global index snapshot.
            val protectedKeys = (localPaths + remotePaths).map(::collisionKey)
            val auditedIndex = index.filter { entry ->
                val key = collisionKey(entry.path)
                protectedKeys.any { pathsCollide(key, it) }
            }
            val workingObjects = readWorkingObjects(repository, auditedIndex.map { it.path }.toSet())
            val status = syncMeasure(SyncPhase.STATUS) { syncCount(SyncCounter.STATUS_CALLS); git.status().call() }
            val reportedWorkingChanges = status.modified + status.missing
            check(auditedIndex.none { entry ->
                entry.path !in reportedWorkingChanges &&
                    (workingObjects[entry.path]?.objectId?.name != entry.objectId ||
                        !compatibleMode(repository, entry.mode, workingObjects[entry.path]?.mode))
            }) {
                // The existing upload contract can also trust stat metadata when staging. Do not
                // proceed if it could omit work that our independent content scan discovered.
                "El contenido local no coincide con el estado de cambios comunicado por Git."
            }
            val ignored = syncMeasure(SyncPhase.IGNORED) {
                syncCount(SyncCounter.IGNORED_ROOTS, status.ignoredNotInIndex.size.toLong())
                status.ignoredNotInIndex.flatMap { ignoredPath ->
                    val path = checkedPath(root, ignoredPath)
                    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                        listOf(ignoredPath)
                    } else {
                        // JGit can report a whole ignored directory. Inspect its existing leaves.
                        Files.walk(path).use { entries ->
                            entries.filter { entry ->
                                syncCount(SyncCounter.IGNORED_VISITED)
                                !Files.isDirectory(entry, NOFOLLOW_LINKS) ||
                                    Files.list(entry).use { !it.findAny().isPresent }
                            }.map { root.relativize(it).toString().replace('\\', '/') }
                                .iterator().asSequence().toList()
                        }
                    }
                }.toSortedSet().also { syncCount(SyncCounter.IGNORED_EXPANDED, it.size.toLong()) }
            }
            // Preserve the exact physical local versions, including staged + unstaged content.
            // Ignored paths are retained for collision checks, without reading unrelated contents.
            val files = localPaths.associateWith {
                fingerprint(checkedPath(root, it))
            }
            return LocalWork(changes, localPaths, ignored, files, index)
        }
    }

    fun hasCollision(local: LocalWork, remotePaths: Set<String>): Boolean {
        return syncMeasure(SyncPhase.COLLISION) {
            val protected = (local.affectedPaths + local.ignoredPaths).map(::collisionKey)
            val remote = remotePaths.map(::collisionKey)
            return protected.any { first -> remote.any { second -> pathsCollide(first, second) } }
        }
    }

    fun verifyCheckoutIsSafe(repository: Repository, head: ObjectId, remote: ObjectId,
                             remotePaths: Set<String>) {
        return syncMeasure(SyncPhase.CHECKOUT_SAFETY) {
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
            syncRevWalk(repository) { walk ->
                val checkout = DirCacheCheckout(repository, walk.parseCommit(head).tree,
                    syncReadIndex(repository), walk.parseCommit(remote).tree)
                checkout.setFailOnConflict(true)
                syncMeasure(SyncPhase.PREFLIGHT) { syncCount(SyncCounter.PREFLIGHT_CALLS); checkout.preScanTwoTrees() }
                check(checkout.conflicts.isEmpty()) { "JGit detectó una condición de checkout insegura."
                }
            }
        }
    }

    fun verifyLocalWorkPreserved(before: LocalWork, after: LocalWork, remotePaths: Set<String>) {
        return syncMeasure(SyncPhase.PRESERVATION) {
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
    }

    fun verifyRemoteFilesApplied(repository: Repository, remote: ObjectId, remotePaths: Set<String>) {
        return syncMeasure(SyncPhase.REMOTE_APPLIED) {
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
    }

    private fun readIndex(repository: Repository): List<IndexEntry> {
        return syncMeasure(SyncPhase.INDEX_SNAPSHOT) {
            val cache = syncReadIndex(repository)
            return (0 until cache.entryCount).map { position ->
                val entry = cache.getEntry(position)
                IndexEntry(entry.pathString, entry.stage, entry.objectId.name, entry.rawMode,
                    entry.isAssumeValid, entry.isSkipWorkTree, entry.isIntentToAdd)
            }
        }
    }

    private fun readTree(repository: Repository, commit: ObjectId): Map<String, TreeEntry> =
        syncMeasure(SyncPhase.TREE) {
            syncRevWalk(repository) { revisions ->
                TreeWalk(repository).use { walk ->
                    walk.addTree(revisions.parseCommit(commit).tree)
                    walk.isRecursive = true
                    buildMap {
                        while (walk.next()) {
                            syncCount(SyncCounter.TREE_ENTRIES)
                            put(walk.pathString, TreeEntry(walk.getObjectId(0), walk.getRawMode(0)))
                        }
                    }
                }
            }
        }

    private fun readWorkingObjects(repository: Repository, paths: Set<String>): Map<String, TreeEntry> {
        return syncMeasure(SyncPhase.WORKING_OBJECTS) {
        syncCount(SyncCounter.WORKING_PATHS, paths.size.toLong())
            if (paths.isEmpty()) return emptyMap()
            return syncRevWalk(repository) { revisions ->
                TreeWalk(repository).use { walk ->
                    walk.addTree(revisions.parseCommit(requireNotNull(repository.resolve("HEAD"))).tree)
                    val index = syncReadIndex(repository)
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
                            val objectId = syncMeasure(SyncPhase.GIT_OBJECT_HASH) {
                                syncCount(SyncCounter.WORKING_OBJECTS)
                                walk.getObjectId(working)
                            }
                            put(walk.pathString, TreeEntry(objectId, walk.getRawMode(working)))
                        }
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
        syncCount(SyncCounter.NORMALIZATIONS)
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

    private fun pathsCollide(first: String, second: String): Boolean {
        syncCount(SyncCounter.COMPARISONS)
        return first == second || first.startsWith("$second/") || second.startsWith("$first/")
    }

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
        return syncMeasure(SyncPhase.FINGERPRINT) {
            syncCount(SyncCounter.FINGERPRINTS)
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
                    val meter = SyncPerformance.current()
                    var readNanos = 0L
                    var digestNanos = 0L
                    var reads = 0L
                    var updates = 0L
                    var bytes = 0L
                    try {
                        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                            syncCount(SyncCounter.SHA256_FILES)
                            val buffer = ByteArray(8192)
                            while (true) {
                                val readStart = meter?.now()
                                val count = try { input.read(buffer) } finally {
                                    if (readStart != null) { readNanos += meter.now() - readStart; reads++ }
                                }
                                if (count < 0) break
                                bytes += count
                                val digestStart = meter?.now()
                                try { digest.update(buffer, 0, count) } finally {
                                    if (digestStart != null) { digestNanos += meter.now() - digestStart; updates++ }
                                }
                            }
                        }
                    } finally {
                        meter?.sample(SyncPhase.SHA_READ, readNanos, reads)
                        meter?.sample(SyncPhase.SHA_DIGEST, digestNanos, updates)
                        meter?.count(SyncCounter.SHA256_BYTES, bytes)
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
                kind == "file" && Files.isExecutable(path)).also { syncCount(SyncCounter.SHA256_HASHES) }
        }
    }
}
