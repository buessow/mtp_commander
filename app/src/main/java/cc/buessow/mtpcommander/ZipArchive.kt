package cc.buessow.mtpcommander

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Lists the folders in a zip file and extracts from it; zip files open read-only, like a folder. */
internal object ZipArchive {
    /** Item ids in a zip are this prefix and the path inside the zip. */
    const val ID_PREFIX = "zip:"

    fun isZip(name: String) = name.endsWith(".zip", ignoreCase = true)

    /** The path inside the zip of an item that [list] returned. */
    fun path(item: CommanderItem) = item.id.removePrefix(ID_PREFIX)

    /** The folders and files directly in [folder] ("" for the top), folders first, as items whose ids are their paths. */
    fun list(zip: File, folder: String): List<CommanderItem> = open(zip) { file ->
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val folders = linkedMapOf<String, Long>()
        val files = mutableListOf<CommanderItem>()
        for (entry in file.entries()) {
            val rest = name(entry).takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() } ?: continue
            val first = rest.substringBefore('/')
            // Folders often have no entry of their own; they show up as the start of their files' paths.
            if (entry.isDirectory && !rest.contains('/')) folders[first] = entry.time
            else if (rest.contains('/')) folders.putIfAbsent(first, 0)
            else files += CommanderItem("$ID_PREFIX$prefix$first", first, false, entry.size, entry.time.coerceAtLeast(0))
        }
        folders.map { (name, time) -> CommanderItem("$ID_PREFIX$prefix$name", name, true, -1, time.coerceAtLeast(0)) }
            .sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
    }

    /** Runs [block] on the contents of the file at [path] in [zip]. */
    fun <T> read(zip: File, path: String, block: (InputStream) -> T): T = open(zip) { file ->
        val entry = file.entries().asSequence().firstOrNull { !it.isDirectory && name(it) == path }
            ?: error("$path is not in the zip file")
        file.getInputStream(entry).use(block)
    }

    /** Extracts the file or folder at [path] in [zip] to the new [to], reporting bytes written of the total. */
    fun extract(zip: File, path: String, to: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) = open(zip) { file ->
        val entries = file.entries().asSequence().filter { name(it) == path || name(it).startsWith("$path/") }.toList()
        check(entries.isNotEmpty()) { "$path is not in the zip file" }
        check(!to.exists()) { "${to.name} already exists" }
        val total = entries.sumOf { it.size.coerceAtLeast(0) }
        var copied = 0L
        if (entries.any { name(it) != path }) check(to.mkdir()) { "Could not create ${to.name}" }
        for (entry in entries) {
            val relative = name(entry).removePrefix(path).removePrefix("/")
            // Each part must be a plain name, so a crafted entry like "../x" cannot write outside [to].
            val parts = relative.split('/').filter { it.isNotEmpty() }
            check(parts.none { it == "." || it == ".." || it.contains('\\') }) { "Unsafe name in zip: ${name(entry)}" }
            val target = parts.fold(to) { dir, part -> File(dir, part) }
            if (entry.isDirectory) { target.mkdirs(); continue }
            target.parentFile?.mkdirs()
            file.getInputStream(entry).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, total)
                    }
                }
            }
            if (entry.time > 0) target.setLastModified(entry.time)
        }
    }

    // Some zip tools write backslashes; folder entries end in "/", which callers handle through isDirectory.
    private fun name(entry: ZipEntry) = entry.name.replace('\\', '/').trimStart('/').trimEnd('/')

    /** Opens [zip] for [block]; names that are not UTF-8 are read in the DOS code page that old zip tools use. */
    private fun <T> open(zip: File, block: (ZipFile) -> T): T = try {
        ZipFile(zip).use(block)
    } catch (e: IllegalArgumentException) {
        ZipFile(zip, Charset.forName("IBM437")).use(block)
    } catch (e: java.util.zip.ZipException) {
        if (e.message?.contains("name", ignoreCase = true) != true) throw e
        ZipFile(zip, Charset.forName("IBM437")).use(block)
    }
}
