package cc.buessow.mtpcommander

import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.FieldBase
import com.garmin.fit.Profile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

/** A titled table; a FIT file has one for its header and one per message type. */
internal data class ContentSection(val title: String, val columns: List<String>, val rows: List<List<String>>)

/** What the viewer shows for a file: its text, its FIT sections, or why it cannot show it. */
internal data class FileView(val name: String, val busy: Boolean = false, val progress: Float? = null,
    val lines: List<String>? = null, val sections: List<ContentSection> = emptyList(), val message: String? = null)

internal object FileContents {
    /** Text files are shown up to this many bytes. */
    const val TEXT_LIMIT = 1 shl 20
    /** Larger files are not read at all; FIT files are read whole. */
    const val FILE_LIMIT = 64L shl 20
    // FIT time stamps count seconds from 1989-12-31 00:00 UTC.
    private val semicircles = 180.0 / (1L shl 31)

    fun isFit(bytes: ByteArray) = bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT"

    /** Text has no control characters other than tabs, line breaks, form feeds and escapes. */
    fun isText(bytes: ByteArray) = bytes.none { val b = it.toInt() and 0xff; b < 0x20 && b !in 0x09..0x0d && b != 0x1b }

    /** Reads what the viewer shows from [input]: a whole FIT file, or the start of anything else. */
    fun view(name: String, input: InputStream): FileView {
        val start = read(input, 12)
        if (isFit(start)) return view(name, start + read(input, FILE_LIMIT.toInt()), truncated = false)
        val bytes = start + read(input, TEXT_LIMIT - start.size)
        return view(name, bytes, truncated = input.read() != -1)
    }

    // InputStream.readNBytes needs Android 13.
    private fun read(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Reads [bytes] as the viewer shows it; [truncated] means only the start of the file is in [bytes]. */
    fun view(name: String, bytes: ByteArray, truncated: Boolean): FileView = when {
        isFit(bytes) -> FileView(name, sections = fitSections(bytes))
        isText(bytes) -> FileView(name, lines = String(bytes, Charsets.UTF_8).lines(),
            message = if (truncated) "Only the first ${TEXT_LIMIT / 1024} kB are shown." else null)
        else -> FileView(name, message = "$name is neither a text file nor a FIT file.")
    }

    /** The file header, then one table per message type in the order they first appear. */
    fun fitSections(bytes: ByteArray): List<ContentSection> {
        class Table(val title: String) {
            val columns = LinkedHashMap<String, String>()
            val rows = mutableListOf<Map<String, String>>()
        }
        val tables = LinkedHashMap<Int, Table>()
        val failure = runCatching {
            Decode().read(ByteArrayInputStream(bytes)) { mesg ->
                // Messages the profile does not know are all called "unknown" too.
                val table = tables.getOrPut(mesg.num) {
                    Table(mesg.name.takeIf { it != "unknown" } ?: "message ${mesg.num}")
                }
                val row = mutableMapOf<String, String>()
                fun add(key: String, field: FieldBase, name: String) {
                    // Developer fields may have no units.
                    table.columns.getOrPut(key) { name + field.units?.takeIf { it.isNotEmpty() && it != "semicircles" }
                        ?.let { " ($it)" }.orEmpty() }
                    row[key] = format(field)
                }
                // Fields the profile does not know are all called "unknown"; their numbers tell them apart.
                mesg.fields.forEach { add("${it.num}", it, if (it.name == "unknown") "field ${it.num}" else it.name) }
                mesg.developerFields.forEach { add("dev:${it.num}:${it.name}", it, it.name ?: "developer ${it.num}") }
                table.rows += row
            }
        }.exceptionOrNull()
        val sections = tables.values.map { table ->
            ContentSection("${table.title} · ${table.rows.size}", table.columns.values.toList(),
                table.rows.map { row -> table.columns.keys.map { row[it].orEmpty() } })
        }
        return listOf(header(bytes, failure)) + sections
    }

    private fun header(bytes: ByteArray, failure: Throwable?): ContentSection {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = bytes[0].toInt() and 0xff
        val protocol = bytes[1].toInt() and 0xff
        val rows = listOfNotNull(
            listOf("Header size", "$size bytes"),
            listOf("Protocol version", "${protocol shr 4}.${protocol and 0x0f}"),
            listOf("Profile version", "%.2f".format(Locale.ROOT, (data.getShort(2).toInt() and 0xffff) / 100.0)),
            listOf("Data size", "${data.getInt(4).toLong() and 0xffffffffL} bytes"),
            listOf("File size", "${bytes.size} bytes"),
            if (size >= 14) listOf("Header CRC", "0x%04x".format(data.getShort(12).toInt() and 0xffff)) else null,
            failure?.let { listOf("Decoding stopped", it.message ?: it.javaClass.simpleName) })
        return ContentSection("File header", listOf("Field", "Value"), rows)
    }

    private fun format(field: FieldBase): String = (0 until field.numValues).joinToString(", ") { i ->
        val value = field.getValue(i)
        val type = (field as? com.garmin.fit.Field)?.profileType
        when {
            value == null -> ""
            value is Number && (type == Profile.Type.DATE_TIME || type == Profile.Type.LOCAL_DATE_TIME) ->
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(DateTime(value.toLong()).date)
            value is Number && field.getUnits(i) == "semicircles" -> "%.6f".format(Locale.ROOT, value.toLong() * semicircles)
            value is Number && type != null && Profile.enumValueName(type, value.toLong()).isNullOrEmpty().not() ->
                Profile.enumValueName(type, value.toLong()).lowercase()
            value is Float || value is Double -> "%.3f".format(Locale.ROOT, (value as Number).toDouble())
                .trimEnd('0').trimEnd('.')
            else -> value.toString()
        }
    }
}
