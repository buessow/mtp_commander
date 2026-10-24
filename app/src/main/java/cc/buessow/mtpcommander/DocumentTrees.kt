package cc.buessow.mtpcommander

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal data class DocFolder(val id: String, val name: String)

internal data class DocEntry(val id: String, val name: String, val folder: Boolean, val size: Long, val modified: Long)

/**
 * Folders granted through Android's folder picker, such as USB drives. Android mounts those where only its own
 * storage provider can reach them, so they are read and written through DocumentsContract instead of file paths.
 * Every call blocks and must run off the main thread.
 */
internal class DocumentTrees(private val resolver: ContentResolver) {
    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** The root's display name, or null when the drive is not mounted. */
    fun name(tree: Uri): String? = runCatching {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree)),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun list(tree: Uri, parentId: String): List<DocEntry> {
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            columns, null, null, null) ?: error("The drive is not available")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val folder = it.getString(2) == Document.MIME_TYPE_DIR
                    add(DocEntry(it.getString(0), it.getString(1) ?: "Unnamed", folder,
                        if (folder || it.isNull(3)) -1 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4)))
                }
            }
        }
    }

    fun find(tree: Uri, parentId: String, name: String) = list(tree, parentId).filter { it.name == name }

    /** Copies a document, or a folder with everything in it, to a new local [to]. */
    fun download(tree: Uri, entry: DocEntry, to: File, onProgress: (Long, Long) -> Unit) {
        if (entry.folder) {
            check(to.mkdir()) { "Could not create ${to.name}" }
            list(tree, entry.id).forEach { child ->
                check(validChildName(child.name)) { "Invalid filename ${child.name}" }
                download(tree, child, File(to, child.name), onProgress)
            }
            return
        }
        check(to.createNewFile()) { "Destination already exists" }
        try {
            (resolver.openInputStream(uri(tree, entry.id)) ?: error("Cannot read ${entry.name}")).use { input ->
                FileOutputStream(to).use { copy(input, it, entry.size, onProgress) }
            }
        } catch (e: Exception) { to.delete(); throw e }
    }

    /** Copies a local file or folder under its own name into [parentId]. */
    fun upload(tree: Uri, parentId: String, file: File, onProgress: (Long, Long) -> Unit) {
        check(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Cannot copy link ${file.name}" }
        val parent = uri(tree, parentId)
        if (file.isDirectory) {
            val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, file.name)
                ?: error("Could not create ${file.name}")
            val id = DocumentsContract.getDocumentId(created)
            (file.listFiles() ?: error("Cannot list ${file.name}")).forEach { upload(tree, id, it, onProgress) }
            return
        }
        // A generic type keeps the name as given; with a specific one the provider may append an extension.
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", file.name)
            ?: error("Could not create ${file.name}")
        try {
            (resolver.openOutputStream(created, "w") ?: error("Cannot write ${file.name}")).use { output ->
                file.inputStream().use { copy(it, output, file.length(), onProgress) }
            }
        } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, created) }; throw e }
    }

    /** Reads a small document completely, up to [limit] bytes. */
    fun read(tree: Uri, id: String, limit: Int): ByteArray =
        (resolver.openInputStream(uri(tree, id)) ?: error("Cannot read document")).use { input ->
            val data = input.readBytes()
            check(data.size <= limit) { "Document is larger than $limit bytes" }
            data
        }

    /** Deletes a document; the provider removes folders with their contents. */
    fun delete(tree: Uri, id: String) {
        check(DocumentsContract.deleteDocument(resolver, uri(tree, id))) { "Could not delete" }
    }

    private fun uri(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun validChildName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' }

    private fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            onProgress(copied, total)
        }
    }
}
