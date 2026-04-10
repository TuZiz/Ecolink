package ym.ecolink.storage

import ym.ecolink.config.StorageSettings

enum class DatabaseType(
    val id: String,
    val driverClassName: String,
    val defaultPort: Int
) {
    POSTGRESQL("postgresql", "org.postgresql.Driver", 5432),
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", 3306);

    fun jdbcUrl(settings: StorageSettings): String {
        return when (this) {
            POSTGRESQL -> {
                "jdbc:postgresql://${settings.host}:${settings.port}/${settings.database}" +
                    "?reWriteBatchedInserts=true&currentSchema=${settings.schema}"
            }

            MYSQL -> {
                "jdbc:mysql://${settings.host}:${settings.port}/${settings.database}" +
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            }
        }
    }

    companion object {
        fun from(raw: String?): DatabaseType {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: POSTGRESQL
        }
    }
}
