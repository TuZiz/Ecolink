package ym.ecolink.hook.papi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import ym.ecolink.Ecolink
import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.EconomyService

class EcolinkPlaceholderExpansion(
    private val plugin: Ecolink,
    private val economyService: EconomyService
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "ecolink"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) {
            return null
        }
        val identity = AccountIdentity(player.uniqueId, player.name)
        val cached = economyService.peekCached(player.uniqueId)
        if (cached == null) {
            economyService.ensureAccount(identity)
        }
        return when (params.lowercase()) {
            "balance" -> (cached?.balance ?: plugin.bootstrapSettings.startingBalance).toPlainString()
            "balance_formatted" -> plugin.bootstrapSettings.format(cached?.balance ?: plugin.bootstrapSettings.startingBalance)
            "server" -> plugin.bootstrapSettings.serverId
            else -> null
        }
    }
}
