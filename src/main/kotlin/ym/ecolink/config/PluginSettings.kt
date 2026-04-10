package ym.ecolink.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import ym.ecolink.storage.DatabaseType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

data class PluginSettings(
    val serverId: String,
    val workerThreads: Int,
    val defaultCurrencyKey: String,
    val currencies: Map<String, CurrencyDefinition>,
    val cacheTtlMillis: Long,
    val language: LanguageSettings,
    val feature: FeatureSettings,
    val compatibility: CompatibilitySettings,
    val redisSync: RedisSyncSettings,
    val storage: StorageSettings,
    val migration: MigrationSettings
) {
    val balanceScale: Int
        get() = defaultCurrency().scale

    val startingBalance: BigDecimal
        get() = defaultCurrency().startingBalance

    val maxBalanceScale: Int
        get() = currencies.values.maxOf { it.scale }

    fun normalize(amount: BigDecimal): BigDecimal {
        return normalize(defaultCurrencyKey, amount)
    }

    fun normalize(currencyKey: String, amount: BigDecimal): BigDecimal {
        val currency = requireCurrency(currencyKey)
        return amount.setScale(currency.scale, RoundingMode.HALF_UP)
    }

    fun format(amount: BigDecimal): String {
        return format(defaultCurrencyKey, amount)
    }

    fun format(currencyKey: String, amount: BigDecimal): String {
        val currency = requireCurrency(currencyKey)
        return "${currency.symbol}${normalize(currency.key, amount).toPlainString()}"
    }

    fun defaultCurrency(): CurrencyDefinition = requireCurrency(defaultCurrencyKey)

    fun requireCurrency(currencyKey: String): CurrencyDefinition {
        return currencies[currencyKey.lowercase()]
            ?: error("Unknown currency: $currencyKey")
    }

    fun findCurrency(currencyKey: String?): CurrencyDefinition? {
        if (currencyKey.isNullOrBlank()) {
            return defaultCurrency()
        }
        return currencies[currencyKey.lowercase()]
    }

    fun vaultCurrency(): CurrencyDefinition {
        return currencies.values.firstOrNull { it.vaultPrimary } ?: defaultCurrency()
    }

    fun listCurrencies(): List<CurrencyDefinition> {
        return currencies.values.sortedBy { it.key }
    }

    companion object {
        fun load(config: FileConfiguration): PluginSettings {
            val legacyScale = max(0, config.getInt("economy.scale", 2))
            val legacyStartingBalance = BigDecimal(config.getString("economy.starting-balance", "0.00") ?: "0.00")
            val currencies = loadCurrencies(config, legacyScale, legacyStartingBalance)
            val defaultKey = (config.getString("currencies.default-key") ?: currencies.values.first().key).lowercase()
            require(currencies.containsKey(defaultKey)) { "Default currency '$defaultKey' is not defined." }

            val type = DatabaseType.from(config.getString("storage.type"))
            return PluginSettings(
                serverId = config.getString("server.id", "server-1") ?: "server-1",
                workerThreads = max(2, config.getInt("async.worker-threads", 4)),
                defaultCurrencyKey = defaultKey,
                currencies = currencies,
                cacheTtlMillis = config.getLong("economy.cache-ttl-millis", 2_000L).coerceAtLeast(0L),
                language = LanguageSettings(
                    locale = config.getString("language.locale", "zh_CN") ?: "zh_CN",
                    fallbackLocale = config.getString("language.fallback-locale", "en_US") ?: "en_US",
                    directory = config.getString("language.directory", "lang") ?: "lang"
                ),
                feature = FeatureSettings(
                    topPageSize = max(5, config.getInt("feature.top-page-size", 10)),
                    ledgerPageSize = max(5, config.getInt("feature.ledger-page-size", 10))
                ),
                compatibility = CompatibilitySettings(
                    vault = VaultSettings(
                        enabled = config.getBoolean("compatibility.vault.enabled", true),
                        syncTimeoutMillis = config.getLong("compatibility.vault.sync-timeout-millis", 1_500L)
                            .coerceAtLeast(250L)
                    ),
                    placeholderApi = PlaceholderSettings(
                        enabled = config.getBoolean("compatibility.placeholderapi.enabled", true)
                    )
                ),
                redisSync = RedisSyncSettings(
                    enabled = config.getBoolean("sync.redis.enabled", false),
                    host = config.getString("sync.redis.host", "127.0.0.1") ?: "127.0.0.1",
                    port = config.getInt("sync.redis.port", 6379),
                    database = config.getInt("sync.redis.database", 0),
                    password = config.getString("sync.redis.password", "") ?: "",
                    channel = config.getString("sync.redis.channel", "ecolink:balance-sync")
                        ?: "ecolink:balance-sync",
                    timeoutMillis = config.getLong("sync.redis.timeout-millis", 2_000L).coerceAtLeast(250L)
                ),
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

        private fun loadCurrencies(
            config: FileConfiguration,
            legacyScale: Int,
            legacyStartingBalance: BigDecimal
        ): Map<String, CurrencyDefinition> {
            val listSection = config.getConfigurationSection("currencies.list")
            if (listSection == null || listSection.getKeys(false).isEmpty()) {
                return mapOf(
                    "coins" to CurrencyDefinition(
                        key = "coins",
                        displayName = "Coins",
                        symbol = config.getString("economy.currency-symbol", "$") ?: "$",
                        scale = legacyScale,
                        startingBalance = legacyStartingBalance.setScale(legacyScale, RoundingMode.HALF_UP),
                        transferable = true,
                        vaultPrimary = true
                    )
                )
            }

            return listSection.getKeys(false).associate { rawKey ->
                val key = rawKey.lowercase()
                val section = listSection.getConfigurationSection(rawKey)
                    ?: error("Currency section missing: $rawKey")
                key to loadCurrency(key, section, legacyScale, legacyStartingBalance)
            }
        }

        private fun loadCurrency(
            key: String,
            section: ConfigurationSection,
            legacyScale: Int,
            legacyStartingBalance: BigDecimal
        ): CurrencyDefinition {
            val scale = max(0, section.getInt("scale", legacyScale))
            val starting = BigDecimal(
                section.getString("starting-balance", legacyStartingBalance.toPlainString())
                    ?: legacyStartingBalance.toPlainString()
            ).setScale(scale, RoundingMode.HALF_UP)
            return CurrencyDefinition(
                key = key,
                displayName = section.getString("display-name", key.replaceFirstChar { it.uppercase() }) ?: key,
                symbol = section.getString("symbol", "$") ?: "$",
                scale = scale,
                startingBalance = starting,
                transferable = section.getBoolean("transferable", true),
                vaultPrimary = section.getBoolean("vault-primary", false)
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

data class CurrencyDefinition(
    val key: String,
    val displayName: String,
    val symbol: String,
    val scale: Int,
    val startingBalance: BigDecimal,
    val transferable: Boolean,
    val vaultPrimary: Boolean
)

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

data class LanguageSettings(
    val locale: String,
    val fallbackLocale: String,
    val directory: String
)

data class FeatureSettings(
    val topPageSize: Int,
    val ledgerPageSize: Int
)

data class CompatibilitySettings(
    val vault: VaultSettings,
    val placeholderApi: PlaceholderSettings
)

data class VaultSettings(
    val enabled: Boolean,
    val syncTimeoutMillis: Long
)

data class PlaceholderSettings(
    val enabled: Boolean
)

data class RedisSyncSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val database: Int,
    val password: String,
    val channel: String,
    val timeoutMillis: Long
)
