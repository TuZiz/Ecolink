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
        val settings = plugin.activeSettings()

        return when {
            params.equals("server", ignoreCase = true) -> settings.serverId
            params.equals("currency", ignoreCase = true) -> settings.defaultCurrency().displayName
            params.equals("balance", ignoreCase = true) -> balancePlain(identity, settings.defaultCurrencyKey)
            params.equals("balance_formatted", ignoreCase = true) -> balanceFormatted(identity, settings.defaultCurrencyKey)
            params.startsWith("balance_formatted_", ignoreCase = true) -> {
                val currency = settings.findCurrency(params.substringAfter("balance_formatted_")) ?: return null
                balanceFormatted(identity, currency.key)
            }

            params.startsWith("balance_", ignoreCase = true) -> {
                val currency = settings.findCurrency(params.substringAfter('_')) ?: return null
                balancePlain(identity, currency.key)
            }

            else -> null
        }
    }

    private fun balancePlain(identity: AccountIdentity, currencyKey: String): String {
        val settings = plugin.activeSettings()
        val cached = economyService.peekCached(identity.uuid, currencyKey)
        if (cached == null) {
            economyService.ensureAccount(identity, currencyKey)
        }
        val fallback = settings.requireCurrency(currencyKey).startingBalance
        return (cached?.balance ?: fallback).toPlainString()
    }

    private fun balanceFormatted(identity: AccountIdentity, currencyKey: String): String {
        val settings = plugin.activeSettings()
        val cached = economyService.peekCached(identity.uuid, currencyKey)
        if (cached == null) {
            economyService.ensureAccount(identity, currencyKey)
        }
        val fallback = settings.requireCurrency(currencyKey).startingBalance
        return settings.format(currencyKey, cached?.balance ?: fallback)
    }
}
