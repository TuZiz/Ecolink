package ym.ecolink.config

import org.bukkit.configuration.file.FileConfiguration
import ym.ecolink.storage.DatabaseType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

data class PluginSettings(
    val serverId: String,
    val workerThreads: Int,
    val currencySymbol: String,
    val balanceScale: Int,
    val startingBalance: BigDecimal,
    val cacheTtlMillis: Long,
    val storage: StorageSettings,
    val migration: MigrationSettings
) {
    fun normalize(amount: BigDecimal): BigDecimal {
        return amount.setScale(balanceScale, RoundingMode.HALF_UP)
    }

    fun format(amount: BigDecimal): String {
        return "$currencySymbol${normalize(amount).toPlainString()}"
    }

    companion object {
        fun load(config: FileConfiguration): PluginSettings {
            val scale = max(0, config.getInt("economy.scale", 2))
            val type = DatabaseType.from(config.getString("storage.type"))
            val startingBalance = BigDecimal(config.getString("economy.starting-balance", "0.00") ?: "0.00")
            return PluginSettings(
                serverId = config.getString("server.id", "server-1") ?: "server-1",
                workerThreads = max(2, config.getInt("async.worker-threads", 4)),
                currencySymbol = config.getString("economy.currency-symbol", "$") ?: "$",
                balanceScale = scale,
                startingBalance = startingBalance.setScale(scale, RoundingMode.HALF_UP),
                cacheTtlMillis = config.getLong("economy.cache-ttl-millis", 2_000L).coerceAtLeast(0L),
                storage = StorageSettings(
                    type = type,
                    host = config.getString("storage.host", "127.0.0.1") ?: "127.0.0.1",
                    port = config.getInt("storage.port", type.defaultPort),
                    database = config.getString("storage.database", "ecolink") ?: "ecolink",
                    schema = config.getString("storage.schema", "public") ?: "public",
                    username = config.getString("storage.username", "postgres") ?: "postgres",
                    password = config.getString("storage.password", "change-me") ?: "change-me",
                    tablePrefix = sanitizePrefix(config.getString("storage.table-prefix", "ecolink_") ?: "ecolink_"),
                    maximumPoolSize = max(2, config.getInt("storage.pool.maximum-size", 8)),
                    minimumIdle = max(1, config.getInt("storage.pool.minimum-idle", 2)),
                    connectionTimeoutMillis = config.getLong("storage.pool.connection-timeout-millis", 10_000L)
                        .coerceAtLeast(1_000L)
                ),
                migration = MigrationSettings(
                    cmiDataFolder = config.getString("migration.cmi-directory", "plugins/CMI") ?: "plugins/CMI",
                    essentialsDataFolder = config.getString("migration.essentials-directory", "plugins/Essentials")
                        ?: "plugins/Essentials"
                )
            )
        }

        private fun sanitizePrefix(value: String): String {
            val filtered = value.filter { it.isLetterOrDigit() || it == '_' }
            if (filtered.isBlank()) {
                return "ecolink_"
            }
            return if (filtered.endsWith("_")) filtered else "${filtered}_"
        }
    }
}

data class StorageSettings(
    val type: DatabaseType,
    val host: String,
    val port: Int,
    val database: String,
    val schema: String,
    val username: String,
    val password: String,
    val tablePrefix: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeoutMillis: Long
)

data class MigrationSettings(
    val cmiDataFolder: String,
    val essentialsDataFolder: String
)
