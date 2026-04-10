package ym.ecolink.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import ym.ecolink.Ecolink
import ym.ecolink.economy.AccountIdentity
import java.util.logging.Level

class PlayerLifecycleListener(
    private val plugin: Ecolink
) : Listener {

    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        val runtime = plugin.runtimeOrNull() ?: return
        runtime.economyService.ensureAccount(AccountIdentity(event.uniqueId, event.name)).exceptionally { error ->
            plugin.logger.log(Level.WARNING, "Failed to warm account for ${event.name}.", error.cause ?: error)
            null
        }
    }
}
