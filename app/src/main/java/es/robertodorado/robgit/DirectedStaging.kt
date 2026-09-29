package es.robertodorado.robgit

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Stage the latest working versions of the fresh status snapshot, as the previous add(.) did. */
internal object DirectedStaging {
    fun stage(git: Git, changes: WorkingTreeChanges) {
        // A staged addition can also be missing, and a staged deletion can have been
        // recreated. Choose from the current filesystem, matching the old add/update policy.
        val deletedPaths = changes.deletedFiles.filter { path ->
            val file = git.repository.workTree.toPath().resolve(path)
            !Files.exists(file, NOFOLLOW_LINKS) || Files.isDirectory(file, NOFOLLOW_LINKS)
        }.toSortedSet()
        val writes = ((changes.newFiles + changes.modifiedFiles + changes.stagedFiles) - deletedPaths).toSortedSet()
        require((writes + deletedPaths).none { it.isEmpty() || it == "." })
        syncCount(SyncCounter.GLOBAL_ADD_CALLS, 0)
        if (writes.isNotEmpty()) syncMeasure(SyncPhase.ADD) {
            syncCount(SyncCounter.ADD_CALLS)
            syncCount(SyncCounter.STAGING_PATHS, writes.size.toLong())
            val add = git.add().setAll(false).setRenormalize(true)
                .setWorkingTreeIterator(ScopedIterator(git.repository, writes))
            writes.forEach { add.addFilepattern(it) }
            add.call()
        }
        // JGit already removes conflicting entries when staging type replacements.
        // Update only exact entries that remain: using a removed file's old name as
        // a prefix after adding children would otherwise stage those children again.
        // Already staged removals also need no update.
        val deletes = if (deletedPaths.isEmpty()) emptySet() else {
            val index = syncReadIndex(git.repository)
            deletedPaths.filter { index.getEntry(it) != null }.toSortedSet()
        }
        if (deletes.isNotEmpty()) syncMeasure(SyncPhase.ADD_UPDATE) {
            syncCount(SyncCounter.ADD_CALLS)
            syncCount(SyncCounter.STAGING_DELETE_PATHS, deletes.size.toLong())
            val add = git.add().setUpdate(true).setRenormalize(true)
                .setWorkingTreeIterator(ScopedIterator(git.repository, deletes))
            deletes.forEach { add.addFilepattern(it) }
            add.call()
        }
    }

    /**
     * Preserve JGit 7.8's default renormalize=true policy, with its path filter applied
     * before opening content. Exact leaf membership prevents a replaced file's new
     * children being added accidentally through the directory-prefix path filter.
     * JGit still handles attributes, ignored tracked files, symlinks and subtree state.
     */
    private class ScopedIterator : FileTreeIterator {
        private val selected: Set<String>
        private val stagingRepository: Repository

        constructor(repository: Repository, selected: Set<String>) : super(repository) {
            this.stagingRepository = repository
            this.selected = selected
        }

        private constructor(parent: ScopedIterator, directory: File) :
            super(parent, directory, parent.stagingRepository.fs) {
            stagingRepository = parent.stagingRepository
            selected = parent.selected
        }

        override fun openEntryStream(): InputStream {
            check(entryPathString in selected) { "El staging intentó abrir una ruta ajena al conjunto seleccionado." }
            val stream = super.openEntryStream()
            syncCount(SyncCounter.STAGING_STREAMS)
            syncCount(SyncCounter.STAGING_STREAM_BYTES, 0)
            return object : FilterInputStream(stream) {
                override fun read(): Int = `in`.read().also {
                    if (it >= 0) syncCount(SyncCounter.STAGING_STREAM_BYTES)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(buffer, offset, length).also {
                        if (it > 0) syncCount(SyncCounter.STAGING_STREAM_BYTES, it.toLong())
                    }
            }
        }

        override fun isEntryIgnored(): Boolean {
            val path = entryPathString
            if (selected.none { it == path || it.startsWith("$path/") }) return true
            return super.isEntryIgnored()
        }

        override fun enterSubtree(): AbstractTreeIterator =
            ScopedIterator(this, (current() as FileEntry).file)
    }
}
