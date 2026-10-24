package cc.buessow.mtpcommander

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Names Garmin devices from GARMIN/Device.fit, whose file_id record carries the product number. */
internal object GarminModel {
    private const val GARMIN = 1
    private var products: Map<Int, String>? = null

    /** The product number in a FIT file's file_id record, or null if it is not a Garmin device. */
    fun productId(fit: ByteArray): Int? = runCatching {
        val data = ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = fit[0].toInt() and 0xff
        val end = minOf(fit.size, headerSize + data.getInt(4))
        // Field numbers and sizes per local message type, from the definition records.
        class Definition(val global: Int, val bigEndian: Boolean, val fields: List<Pair<Int, Int>>, val extra: Int)
        val definitions = arrayOfNulls<Definition>(16)
        var position = headerSize
        while (position < end) {
            val header = fit[position++].toInt() and 0xff
            if (header and 0x80 != 0) {
                // A compressed timestamp header introduces a data record.
                val definition = definitions[(header shr 5) and 0x03] ?: return null
                position += definition.fields.sumOf { it.second } + definition.extra
                continue
            }
            val local = header and 0x0f
            if (header and 0x40 != 0) {
                val bigEndian = fit[position + 1].toInt() == 1
                val global = ByteBuffer.wrap(fit, position + 2, 2)
                    .order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                val count = fit[position + 4].toInt() and 0xff
                position += 5
                val fields = (0 until count).map { i ->
                    (fit[position + i * 3].toInt() and 0xff) to (fit[position + i * 3 + 1].toInt() and 0xff)
                }
                position += count * 3
                var extra = 0
                if (header and 0x20 != 0) {
                    // Developer fields follow the regular ones in every data record.
                    val developerCount = fit[position++].toInt() and 0xff
                    extra = (0 until developerCount).sumOf { fit[position + it * 3 + 1].toInt() and 0xff }
                    position += developerCount * 3
                }
                definitions[local] = Definition(global, bigEndian, fields, extra)
                continue
            }
            val definition = definitions[local] ?: return null
            if (definition.global == 0) {
                val record = ByteBuffer.wrap(fit).order(if (definition.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                var offset = position
                var manufacturer: Int? = null
                var product: Int? = null
                for ((number, size) in definition.fields) {
                    if (size == 2 && number == 1) manufacturer = record.getShort(offset).toInt() and 0xffff
                    if (size == 2 && number == 2) product = record.getShort(offset).toInt() and 0xffff
                    offset += size
                }
                return if (manufacturer == GARMIN) product?.takeIf { it != 0xffff } else null
            }
            position += definition.fields.sumOf { it.second } + definition.extra
        }
        null
    }.getOrNull()

    /** A name like "Garmin Edge 1030 Plus" for a Garmin product number. */
    fun name(context: Context, productId: Int): String {
        val table = products ?: context.resources.openRawResource(R.raw.garmin_products).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore(' ').toInt() to line.substringAfter(' ') }
        }.also { products = it }
        return table[productId]?.let { "Garmin ${pretty(it)}" } ?: "Garmin device $productId"
    }

    // The SDK's identifiers are camel case with numbers attached, e.g. edge1030Plus or fr955.
    private fun pretty(identifier: String): String =
        identifier.split(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])|_")).filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                when (word) {
                    "fr" -> "Forerunner"
                    else -> word.replaceFirstChar { it.uppercase() }
                }
            }
}
