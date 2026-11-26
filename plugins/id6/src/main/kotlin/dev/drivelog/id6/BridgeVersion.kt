package dev.drivelog.id6

/**
 * The version of the head-unit plugin, from its hello ("drivelog-bridge-0.2.0 NBT Evo"). A head unit with an older
 * plugin than [MIN] still works, but misses values this app expects: the status says to update it.
 */
data class BridgeVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<BridgeVersion> {
    override fun compareTo(other: BridgeVersion): Int =
        compareValuesBy(this, other, BridgeVersion::major, BridgeVersion::minor, BridgeVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** The oldest head-unit plugin with everything this app reads (18 CDS values: car speed, fuel counter). */
        val MIN = BridgeVersion(0, 2, 0)

        private val PATTERN = Regex("""drivelog-bridge-(\d+)\.(\d+)\.(\d+)""")

        /** Null when [hello] is not a head-unit plugin's name with a version. */
        fun parse(hello: String?): BridgeVersion? {
            val m = PATTERN.find(hello ?: return null) ?: return null
            val (a, b, c) = m.destructured
            return BridgeVersion(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null)
        }

        /** True when [hello] names a plugin older than [MIN]. An unknown version is not treated as old. */
        fun isOutdated(hello: String?): Boolean = parse(hello)?.let { it < MIN } ?: false
    }
}
