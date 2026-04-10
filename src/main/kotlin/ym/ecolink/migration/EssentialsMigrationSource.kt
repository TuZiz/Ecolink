package ym.ecolink.migration

import ym.ecolink.economy.ImportedBalance
import ym.ecolink.util.YamlMaps
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class EssentialsMigrationSource(
    private val rootDirectory: Path,
    private val currencyKey: String,
    private val scale: Int
) {

    fun load(): List<ImportedBalance> {
        val userDataDirectory = rootDirectory.resolve("userdata")
        require(Files.isDirectory(userDataDirectory)) { "Essentials userdata directory not found: $userDataDirectory" }

        val results = mutableListOf<ImportedBalance>()
        Files.list(userDataDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("yml", ignoreCase = true) }
                .sorted()
                .forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                    val data = YamlMaps.load(file)
                    val username = YamlMaps.string(data["last-account-name"]) ?: uuid.toString()
                    val amount = parseAmount(data["money"])
                    results += ImportedBalance(uuid, username, currencyKey, amount)
                }
        }
        return results
    }

    private fun parseAmount(value: Any?): BigDecimal {
        val amount = when (value) {
            is Number -> BigDecimal(value.toString())
            is String -> BigDecimal(value)
            else -> BigDecimal.ZERO
        }
        return amount.setScale(scale, RoundingMode.HALF_UP)
    }
}
