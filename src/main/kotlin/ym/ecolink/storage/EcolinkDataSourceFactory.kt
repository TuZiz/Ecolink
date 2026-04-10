package ym.ecolink.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.ecolink.config.StorageSettings

object EcolinkDataSourceFactory {

    fun create(settings: StorageSettings): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = settings.type.jdbcUrl(settings)
            driverClassName = settings.type.driverClassName
            maximumPoolSize = if (settings.type == DatabaseType.SQLITE) 1 else settings.maximumPoolSize
            minimumIdle = if (settings.type == DatabaseType.SQLITE) 1 else settings.minimumIdle
            connectionTimeout = settings.connectionTimeoutMillis
            isAutoCommit = false
            poolName = "EcolinkPool"
        }

        when (settings.type) {
            DatabaseType.SQLITE -> {
                config.connectionTestQuery = "SELECT 1"
            }

            DatabaseType.MYSQL -> {
                config.username = settings.username
                config.password = settings.password
                config.addDataSourceProperty("cachePrepStmts", "true")
                config.addDataSourceProperty("prepStmtCacheSize", "250")
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }

            DatabaseType.POSTGRESQL -> {
                config.username = settings.username
                config.password = settings.password
            }
        }

        return HikariDataSource(config)
    }
}
