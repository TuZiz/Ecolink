package ym.ecolink.migration

data class SourceMigrationReport(
    val source: String,
    val discovered: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val sampleErrors: List<String>
)

data class MigrationSummary(
    val reports: List<SourceMigrationReport>
) {
    val discovered: Int = reports.sumOf { it.discovered }
    val inserted: Int = reports.sumOf { it.inserted }
    val updated: Int = reports.sumOf { it.updated }
    val skipped: Int = reports.sumOf { it.skipped }
    val failed: Int = reports.sumOf { it.failed }

    fun sourceLabel(): String = reports.joinToString("+") { it.source }
}

data class StorageMigrationReport(
    val table: String,
    val discovered: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val sampleErrors: List<String>
)

data class StorageMigrationSummary(
    val source: String,
    val target: String,
    val reports: List<StorageMigrationReport>
) {
    val discovered: Int = reports.sumOf { it.discovered }
    val inserted: Int = reports.sumOf { it.inserted }
    val updated: Int = reports.sumOf { it.updated }
    val skipped: Int = reports.sumOf { it.skipped }
    val failed: Int = reports.sumOf { it.failed }
}

enum class MigrationKind {
    CMI,
    ESSENTIALS,
    ALL;

    companion object {
        fun from(raw: String): MigrationKind? {
            return when (raw.lowercase()) {
                "cmi" -> CMI
                "ess", "essentials", "essentialsx" -> ESSENTIALS
                "all" -> ALL
                else -> null
            }
        }
    }
}
