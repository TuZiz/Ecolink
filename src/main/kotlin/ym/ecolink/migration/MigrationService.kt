package ym.ecolink.migration

import ym.ecolink.config.PluginSettings
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.storage.EconomyRepository
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class MigrationService(
    private val settings: PluginSettings,
    private val repository: EconomyRepository,
    private val dispatcher: ServerTaskDispatcher
) {

    fun migrate(kind: MigrationKind, overwrite: Boolean): CompletableFuture<MigrationSummary> {
        return dispatcher.supplyAsync {
            val reports = mutableListOf<SourceMigrationReport>()
            when (kind) {
                MigrationKind.CMI -> reports += migrateCmi(overwrite)
                MigrationKind.ESSENTIALS -> reports += migrateEssentials(overwrite)
                MigrationKind.ALL -> {
                    reports += migrateCmi(overwrite)
                    reports += migrateEssentials(overwrite)
                }
            }
            MigrationSummary(reports)
        }
    }

    private fun migrateCmi(overwrite: Boolean): SourceMigrationReport {
        val currency = settings.defaultCurrency()
        val source = CmiMigrationSource(
            rootDirectory = Path.of(settings.migration.cmiDataFolder),
            currencyKey = currency.key,
            scale = currency.scale
        )
        return repository.importBalances(
            entries = source.load(),
            overwrite = overwrite,
            source = "CMI",
            serverId = settings.serverId
        )
    }

    private fun migrateEssentials(overwrite: Boolean): SourceMigrationReport {
        val currency = settings.defaultCurrency()
        val source = EssentialsMigrationSource(
            rootDirectory = Path.of(settings.migration.essentialsDataFolder),
            currencyKey = currency.key,
            scale = currency.scale
        )
        return repository.importBalances(
            entries = source.load(),
            overwrite = overwrite,
            source = "EssentialsX",
            serverId = settings.serverId
        )
    }
}
