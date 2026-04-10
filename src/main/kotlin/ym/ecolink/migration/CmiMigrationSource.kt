package ym.ecolink.migration

import ym.ecolink.economy.ImportedBalance
import ym.ecolink.util.YamlMaps
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class CmiMigrationSource(
    private val rootDirectory: Path,
    private val currencyKey: String,
    private val scale: Int
) {

    fun load(): List<ImportedBalance> {
        val infoPath = rootDirectory.resolve("Settings").resolve("DataBaseInfo.yml")
        val config = if (Files.exists(infoPath)) YamlMaps.load(infoPath) else emptyMap()
        val storage = YamlMaps.section(config, "storage")
        val method = YamlMaps.string(storage["method"])?.lowercase() ?: "sqlite"
        return when (method) {
            "mysql" -> loadFromMySql(YamlMaps.section(config, "mysql"))
            "sqlite" -> loadFromSqlite(rootDirectory.resolve("cmi.sqlite.db"))
            else -> throw IllegalStateException("Unsupported CMI storage method: $method")
        }
    }

    private fun loadFromSqlite(path: Path): List<ImportedBalance> {
        require(Files.exists(path)) { "CMI sqlite database not found: $path" }
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("SELECT player_uuid, username, Balance, Economy FROM users").use { statement ->
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<ImportedBalance>()
                    while (resultSet.next()) {
                        val uuid = UUID.fromString(resultSet.getString("player_uuid"))
                        val username = resultSet.getString("username") ?: uuid.toString()
                        val balance = extractBalance(
                            resultSet.getBigDecimal("Balance"),
                            resultSet.getString("Economy")
                        )
                        results += ImportedBalance(uuid, username, currencyKey, balance)
                    }
                    return results
                }
            }
        }
    }

    private fun loadFromMySql(mysqlSection: Map<String, Any?>): List<ImportedBalance> {
        val hostPort = YamlMaps.string(mysqlSection["hostname"]) ?: "127.0.0.1:3306"
        val database = YamlMaps.string(mysqlSection["database"]) ?: error("Missing CMI MySQL database")
        val username = YamlMaps.string(mysqlSection["username"]) ?: error("Missing CMI MySQL username")
        val password = YamlMaps.string(mysqlSection["password"]) ?: ""
        val tablePrefix = YamlMaps.string(mysqlSection["tablePrefix"]) ?: "CMI_"
        val (host, port) = splitHostPort(hostPort)

        Class.forName("com.mysql.cj.jdbc.Driver")
        val url = "jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        DriverManager.getConnection(url, username, password).use { connection ->
            connection.prepareStatement(
                "SELECT player_uuid, username, Balance, Economy FROM ${tablePrefix}users"
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<ImportedBalance>()
                    while (resultSet.next()) {
                        val uuid = UUID.fromString(resultSet.getString("player_uuid"))
                        val usernameValue = resultSet.getString("username") ?: uuid.toString()
                        val balance = extractBalance(
                            resultSet.getBigDecimal("Balance"),
                            resultSet.getString("Economy")
                        )
                        results += ImportedBalance(uuid, usernameValue, currencyKey, balance)
                    }
                    return results
                }
            }
        }
    }

    private fun extractBalance(balance: BigDecimal?, economyField: String?): BigDecimal {
        if (balance != null) {
            return balance.setScale(scale, RoundingMode.HALF_UP)
        }
        if (economyField.isNullOrBlank()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
        }
        val token = economyField.substringAfter("%%", "0")
        return BigDecimal(token).setScale(scale, RoundingMode.HALF_UP)
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        val separator = hostPort.lastIndexOf(':')
        if (separator <= 0) {
            return hostPort to 3306
        }
        val host = hostPort.substring(0, separator)
        val port = hostPort.substring(separator + 1).toIntOrNull() ?: 3306
        return host to port
    }
}
