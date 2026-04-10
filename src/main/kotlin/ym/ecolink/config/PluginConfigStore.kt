package ym.ecolink.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PluginConfigStore(
    private val plugin: JavaPlugin
) {

    private val lock = ReentrantLock()
    private val configFile: File
        get() = plugin.dataFolder.resolve("config.yml")

    fun loadSettings(): PluginSettings = lock.withLock {
        PluginSettings.load(YamlConfiguration.loadConfiguration(configFile))
    }

    fun updateEnabledCurrencies(enabled: Collection<String>): PluginSettings = lock.withLock {
        val yaml = YamlConfiguration.loadConfiguration(configFile)
        yaml.set(
            "currencies.enabled",
            enabled.map { it.lowercase() }
                .distinct()
                .sorted()
        )
        yaml.save(configFile)
        PluginSettings.load(yaml)
    }
}
