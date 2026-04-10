package ym.ecolink.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.ecolink.config.StorageSettings

object EcolinkDataSourceFactory {

    fun create(settings: StorageSettings): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = settings.type.jdbcUrl(settings)
            username = settings.username
            password = settings.password
            driverClassName = settings.type.driverClassName
            maximumPoolSize = settings.maximumPoolSize
            minimumIdle = settings.minimumIdle
            connectionTimeout = settings.connectionTimeoutMillis
            isAutoCommit = false
            poolName = "EcolinkPool"
        }

        if (settings.type == DatabaseType.MYSQL) {
            config.addDataSourceProperty("cachePrepStmts", "true")
            config.addDataSourceProperty("prepStmtCacheSize", "250")
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        return HikariDataSource(config)
    }
}
