package ym.ecolink.i18n

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.config.LanguageSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MessageService(
    private val plugin: JavaPlugin,
    private val settings: LanguageSettings
) : AutoCloseable {

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val audiences: BukkitAudiences = BukkitAudiences.create(plugin)
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()

    private lateinit var activeConfig: YamlConfiguration
    private lateinit var fallbackConfig: YamlConfiguration
    private lateinit var prefixComponent: Component

    fun initialize() {
        saveBundledLanguage("en_US")
        saveBundledLanguage("zh_CN")

        val languageDir = plugin.dataFolder.resolve(settings.directory).apply { mkdirs() }
        fallbackConfig = loadConfig(languageDir.resolve("${settings.fallbackLocale}.yml"))
        activeConfig = if (settings.locale.equals(settings.fallbackLocale, ignoreCase = true)) {
            fallbackConfig
        } else {
            val requestedFile = languageDir.resolve("${settings.locale}.yml")
            if (requestedFile.isFile) loadConfig(requestedFile) else fallbackConfig
        }
        prefixComponent = miniMessage.deserialize(resolveString("prefix", resolvePrefix = false))
    }

    fun send(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        audiences.sender(sender).sendMessage(render(path, *placeholders))
    }

    fun sendLines(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        renderLines(path, *placeholders).forEach { component ->
            audiences.sender(sender).sendMessage(component)
        }
    }

    fun render(path: String, vararg placeholders: MessagePlaceholder): Component {
        return deserialize(resolveString(path, resolvePrefix = true), placeholders.asList())
    }

    fun renderLines(path: String, vararg placeholders: MessagePlaceholder): List<Component> {
        return resolveLines(path).map { line -> deserialize(line, placeholders.asList()) }
    }

    fun raw(path: String): String {
        return resolveString(path, resolvePrefix = false)
    }

    override fun close() {
        audiences.close()
    }

    private fun saveBundledLanguage(locale: String) {
        val resourcePath = "lang/$locale.yml"
        if (plugin.getResource(resourcePath) != null) {
            plugin.saveResource(resourcePath, false)
        }
    }

    private fun loadConfig(file: File): YamlConfiguration {
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun resolveString(path: String, resolvePrefix: Boolean): String {
        activeConfig.getString(path)?.let { return it }
        fallbackConfig.getString(path)?.let { return it }
        if (missingKeys.add(path)) {
            plugin.logger.warning("Missing language key: $path")
        }
        return if (resolvePrefix) "<red>Missing language key: <white>$path</white></red>" else path
    }

    private fun resolveLines(path: String): List<String> {
        if (activeConfig.isList(path)) {
            return activeConfig.getStringList(path)
        }
        activeConfig.getString(path)?.let { return listOf(it) }
        if (fallbackConfig.isList(path)) {
            return fallbackConfig.getStringList(path)
        }
        fallbackConfig.getString(path)?.let { return listOf(it) }
        if (missingKeys.add(path)) {
            plugin.logger.warning("Missing language key: $path")
        }
        return listOf("<red>Missing language key: <white>$path</white></red>")
    }

    private fun deserialize(template: String, placeholders: List<MessagePlaceholder>): Component {
        return miniMessage.deserialize(template, resolver(placeholders))
    }

    private fun resolver(placeholders: List<MessagePlaceholder>): TagResolver {
        val resolvers = mutableListOf<TagResolver>(
            Placeholder.component("prefix", prefixComponent)
        )
        placeholders.forEach { placeholder ->
            resolvers += Placeholder.unparsed(placeholder.name, placeholder.value)
        }
        return TagResolver.resolver(resolvers)
    }
}

data class MessagePlaceholder(
    val name: String,
    val value: String
)

fun ph(name: String, value: Any?): MessagePlaceholder {
    return MessagePlaceholder(name, value?.toString() ?: "")
}
