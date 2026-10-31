package cc.buessow.mtpcommander

/**
 * Names Wahoo devices from system_info.txt in their storage root, whose first line is the firmware version, e.g.
 * WD08-13007: a submodel prefix, a firmware branch that changes with updates, and a build number.
 */
internal object WahooModel {
    const val FILE = "system_info.txt"
    // Prefixes from Wahoo's firmware release notes; ROAM v1 units from serial 4721 on report WD instead of WR.
    private val models = mapOf("WR" to "Wahoo ELEMNT ROAM", "WD" to "Wahoo ELEMNT ROAM")

    /** A name for the device whose system_info.txt reads [systemInfo], or null if it is not one. */
    fun name(systemInfo: String): String? {
        val prefix = Regex("([A-Z]{2})\\d+-\\d+").matchEntire(systemInfo.lineSequence().firstOrNull()?.trim().orEmpty())
            ?.groupValues?.get(1) ?: return null
        return models[prefix] ?: "Wahoo device ($prefix)"
    }
}
