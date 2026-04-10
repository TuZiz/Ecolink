@file:Suppress("OVERRIDE_DEPRECATION")

package ym.ecolink.hook.vault

import net.milkbowl.vault.economy.AbstractEconomy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.EconomyService
import ym.ecolink.economy.ReasonTags
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class EcolinkVaultEconomy(
    private val plugin: JavaPlugin,
    private val settings: PluginSettings,
    private val economyService: EconomyService
) : AbstractEconomy() {

    private val currency = settings.vaultCurrency()

    override fun isEnabled(): Boolean = plugin.isEnabled

    override fun getName(): String = "Ecolink"

    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = currency.scale

    override fun format(amount: Double): String = settings.format(currency.key, BigDecimal.valueOf(amount))

    override fun currencyNamePlural(): String = currency.displayName

    override fun currencyNameSingular(): String = currency.displayName

    override fun hasAccount(playerName: String): Boolean = resolve(playerName) != null

    override fun hasAccount(player: OfflinePlayer): Boolean = resolve(player) != null

    override fun hasAccount(playerName: String, worldName: String): Boolean = hasAccount(playerName)

    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = hasAccount(player)

    override fun getBalance(playerName: String): Double = resolve(playerName)?.let { balance(it) } ?: currency.startingBalance.toDouble()

    override fun getBalance(player: OfflinePlayer): Double = resolve(player)?.let { balance(it) } ?: currency.startingBalance.toDouble()

    override fun getBalance(playerName: String, world: String): Double = getBalance(playerName)

    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)

    override fun has(playerName: String, amount: Double): Boolean = getBalance(playerName) >= amount

    override fun has(player: OfflinePlayer, amount: Double): Boolean = getBalance(player) >= amount

    override fun has(playerName: String, worldName: String, amount: Double): Boolean = has(playerName, amount)

    override fun has(player: OfflinePlayer, worldName: String, amount: Double): Boolean = has(player, amount)

    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse {
        val identity = resolve(playerName)
            ?: return failure(amount, "Account not found: $playerName")
        return runMutation(identity, BigDecimal.valueOf(amount), deposit = false)
    }

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        val identity = resolve(player)
            ?: return failure(amount, "Account not found: ${player.name ?: player.uniqueId}")
        return runMutation(identity, BigDecimal.valueOf(amount), deposit = false)
    }

    override fun withdrawPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse = withdrawPlayer(player, amount)

    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse {
        val identity = resolveOrCreate(playerName)
            ?: return failure(amount, "Account not found: $playerName")
        return runMutation(identity, BigDecimal.valueOf(amount), deposit = true)
    }

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        val identity = resolveOrCreate(player)
            ?: return failure(amount, "Account not found: ${player.name ?: player.uniqueId}")
        return runMutation(identity, BigDecimal.valueOf(amount), deposit = true)
    }

    override fun depositPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse = depositPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse = depositPlayer(player, amount)

    override fun createPlayerAccount(playerName: String): Boolean {
        val identity = resolveOrCreate(playerName) ?: return false
        return runCatching { economyService.awaitAccount(identity, currency.key) }.isSuccess
    }

    override fun createPlayerAccount(player: OfflinePlayer): Boolean {
        val identity = resolveOrCreate(player) ?: return false
        return runCatching { economyService.awaitAccount(identity, currency.key) }.isSuccess
    }

    override fun createPlayerAccount(playerName: String, worldName: String): Boolean = createPlayerAccount(playerName)

    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = createPlayerAccount(player)

    override fun createBank(name: String, player: String): EconomyResponse = notImplemented(name)

    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = notImplemented(name)

    override fun deleteBank(name: String): EconomyResponse = notImplemented(name)

    override fun bankBalance(name: String): EconomyResponse = notImplemented(name)

    override fun bankHas(name: String, amount: Double): EconomyResponse = notImplemented(name)

    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = notImplemented(name)

    override fun bankDeposit(name: String, amount: Double): EconomyResponse = notImplemented(name)

    override fun isBankOwner(name: String, playerName: String): EconomyResponse = notImplemented(name)

    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = notImplemented(name)

    override fun isBankMember(name: String, playerName: String): EconomyResponse = notImplemented(name)

    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = notImplemented(name)

    override fun getBanks(): MutableList<String> = mutableListOf()

    private fun resolve(playerName: String): AccountIdentity? {
        Bukkit.getPlayerExact(playerName)?.let { online ->
            return AccountIdentity(online.uniqueId, online.name)
        }
        return runCatching { economyService.awaitIdentity(playerName) }.getOrNull()
    }

    private fun resolve(player: OfflinePlayer): AccountIdentity? {
        player.name?.let { return AccountIdentity(player.uniqueId, it) }
        return runCatching { economyService.awaitIdentity(player.uniqueId.toString()) }.getOrNull()
    }

    private fun resolveOrCreate(playerName: String): AccountIdentity? {
        return resolve(playerName) ?: Bukkit.getPlayerExact(playerName)?.let { AccountIdentity(it.uniqueId, it.name) }
    }

    private fun resolveOrCreate(player: OfflinePlayer): AccountIdentity? {
        return resolve(player) ?: player.name?.let { AccountIdentity(player.uniqueId, it) }
    }

    private fun balance(identity: AccountIdentity): Double {
        return runCatching {
            economyService.awaitAccount(identity, currency.key).balance.toDouble()
        }.getOrElse { currency.startingBalance.toDouble() }
    }

    private fun runMutation(identity: AccountIdentity, amount: BigDecimal, deposit: Boolean): EconomyResponse {
        return try {
            val future = if (deposit) {
                economyService.addBalance(identity, currency.key, amount, "Vault", ReasonTags.VAULT_DEPOSIT)
            } else {
                economyService.takeBalance(identity, currency.key, amount, "Vault", ReasonTags.VAULT_WITHDRAW)
            }
            val record = future.get(settings.compatibility.vault.syncTimeoutMillis, TimeUnit.MILLISECONDS)
            EconomyResponse(amount.toDouble(), record.balance.toDouble(), EconomyResponse.ResponseType.SUCCESS, null)
        } catch (error: Throwable) {
            failure(amount.toDouble(), error.cause?.message ?: error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(amount: Double, message: String): EconomyResponse {
        return EconomyResponse(amount, 0.0, EconomyResponse.ResponseType.FAILURE, message)
    }

    private fun notImplemented(name: String): EconomyResponse {
        return EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank API is not supported: $name")
    }
}
