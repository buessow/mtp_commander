package cc.buessow.mtpcommander

import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesg
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.MesgNum
import java.io.ByteArrayInputStream

/**
 * Names Garmin devices from GARMIN/GarminDevice.xml, which spells out the model, or GARMIN/Device.fit, whose file_id
 * record carries the product number.
 */
internal object GarminModel {
    /** A name like "Garmin inReach Mini 2" from the model description in GarminDevice.xml. */
    fun fromDeviceXml(xml: String): String? {
        val description = Regex("<Model>.*?<Description>([^<]*)</Description>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (description.startsWith("Garmin", ignoreCase = true)) description else "Garmin $description"
    }

    /** The product number in a FIT file's file_id record, or null if it is not a Garmin device. */
    fun productId(fit: ByteArray): Int? {
        var fileId: FileIdMesg? = null
        // Only the start of the file is read; file_id is its first message, so a decode error later on does not matter.
        runCatching {
            Decode().read(ByteArrayInputStream(fit)) { mesg ->
                if (mesg.num == MesgNum.FILE_ID && fileId == null) fileId = FileIdMesg(mesg)
            }
        }
        return fileId?.takeIf { it.manufacturer == Manufacturer.GARMIN }?.product
    }

    /** A name like "Garmin Edge 1030 Plus" for a Garmin product number. */
    fun name(productId: Int): String =
        GarminProduct.getStringFromValue(productId).takeIf { it.isNotEmpty() }?.let { "Garmin ${pretty(it)}" }
            ?: "Garmin device $productId"

    // The SDK's identifiers are upper case with numbers attached, e.g. EDGE_1030_PLUS or FR955.
    private fun pretty(identifier: String): String =
        identifier.lowercase().split(Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])|_")).filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                when (word) {
                    "fr" -> "Forerunner"
                    else -> word.replaceFirstChar { it.uppercase() }
                }
            }
}
