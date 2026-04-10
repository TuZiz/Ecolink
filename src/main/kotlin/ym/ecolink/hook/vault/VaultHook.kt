package ym.ecolink.hook.vault

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.EconomyService

class VaultHook(
    private val plugin: JavaPlugin,
    private val settings: PluginSettings,
    private val economyService: EconomyService
) : AutoCloseable {

    private var provider: Economy? = null

    fun register(): Boolean {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false
        }
        val economy = EcolinkVaultEconomy(plugin, settings, economyService)
        Bukkit.getServicesManager().register(Economy::class.java, economy, plugin, ServicePriority.Normal)
        provider = economy
        return true
    }

    override fun close() {
        provider?.let { Bukkit.getServicesManager().unregister(Economy::class.java, it) }
        provider = null
    }
}
