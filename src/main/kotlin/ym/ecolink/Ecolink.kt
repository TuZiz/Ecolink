package ym.ecolink

import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.hook.papi.EcolinkPlaceholderExpansion
import ym.ecolink.hook.vault.VaultHook
import ym.ecolink.command.EcoCommand
import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.EconomyService
import ym.ecolink.i18n.MessagePlaceholder
import ym.ecolink.i18n.MessageService
import ym.ecolink.listener.PlayerLifecycleListener
import ym.ecolink.migration.MigrationService
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.storage.EcolinkDataSourceFactory
import ym.ecolink.storage.JdbcEconomyRepository
import ym.ecolink.sync.RedisBalanceSyncService
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

class Ecolink : JavaPlugin() {

    lateinit var bootstrapSettings: PluginSettings
        private set

    lateinit var dispatcher: ServerTaskDispatcher
        private set

    lateinit var messages: MessageService
        private set

    lateinit var startupFuture: CompletableFuture<PluginRuntime>
        private set

    @Volatile
    private var runtime: PluginRuntime? = null

    @Volatile
    private var startupFailure: Throwable? = null

    override fun onEnable() {
        saveDefaultConfig()
        bootstrapSettings = PluginSettings.load(config)
        messages = MessageService(this, bootstrapSettings.language).also { it.initialize() }
        dispatcher = ServerTaskDispatcher(this, bootstrapSettings.workerThreads)

        val commandHandler = EcoCommand(this)
        getCommand("ecolink")?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        }
        getCommand("balance")?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        }
        getCommand("pay")?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        }
        getCommand("baltop")?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        }

        server.pluginManager.registerEvents(PlayerLifecycleListener(this), this)

        startupFuture = dispatcher.supplyAsync {
            bootstrap(bootstrapSettings)
        }

        startupFuture.whenComplete { created, error ->
            if (error != null) {
                val cause = unwrap(error)
                startupFailure = cause
                logger.log(Level.SEVERE, "Ecolink failed to initialize.", cause)
                return@whenComplete
            }
            if (!isEnabled) {
                created.shutdown()
                return@whenComplete
            }
            runtime = created
            dispatcher.runGlobal {
                installOptionalBridges(created)
            }
            logger.info(
                "Ecolink initialized. storage=${created.settings.storage.type.id}, " +
                    "serverId=${created.settings.serverId}, " +
                    "currencies=${created.settings.listCurrencies().joinToString(",") { it.key }}"
            )
        }
    }

    override fun onDisable() {
        runtime?.shutdown()
        messages.close()
        dispatcher.shutdown()
    }

    fun runtimeOrNull(): PluginRuntime? = runtime

    fun startupErrorOrNull(): Throwable? = startupFailure

    fun reply(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        messages.send(sender, path, *placeholders)
    }

    fun replyLines(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        messages.sendLines(sender, path, *placeholders)
    }

    fun replyLater(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        dispatcher.runOnSender(sender) {
            reply(sender, path, *placeholders)
        }
    }

    fun replyLinesLater(sender: CommandSender, path: String, vararg placeholders: MessagePlaceholder) {
        dispatcher.runOnSender(sender) {
            replyLines(sender, path, *placeholders)
        }
    }

    private fun bootstrap(settings: PluginSettings): PluginRuntime {
        val dataSource = EcolinkDataSourceFactory.create(settings.storage)
        val repository = JdbcEconomyRepository(
            dataSource = dataSource,
            tablePrefix = settings.storage.tablePrefix,
            settings = settings
        )
        repository.initialize()
        val economyService = EconomyService(settings, repository, dispatcher)
        val migrationService = MigrationService(settings, repository, dispatcher)
        val redisSyncService = if (settings.redisSync.enabled) {
            RedisBalanceSyncService(
                localServerId = settings.serverId,
                defaultCurrencyKey = settings.defaultCurrencyKey,
                settings = settings.redisSync,
                economyService = economyService,
                logger = logger
            ).also { sync ->
                economyService.setMutationListener(sync::publish)
                sync.start()
            }
        } else {
            null
        }
        return PluginRuntime(
            settings = settings,
            dataSource = dataSource,
            repository = repository,
            economyService = economyService,
            migrationService = migrationService,
            redisSyncService = redisSyncService
        )
    }

    private fun installOptionalBridges(runtime: PluginRuntime) {
        if (runtime.settings.compatibility.vault.enabled) {
            val hook = VaultHook(this, runtime.settings, runtime.economyService)
            if (hook.register()) {
                runtime.vaultHook = hook
                logger.info("Vault hook registered.")
            }
        }
        if (runtime.settings.compatibility.placeholderApi.enabled &&
            server.pluginManager.getPlugin("PlaceholderAPI") != null
        ) {
            val expansion = EcolinkPlaceholderExpansion(this, runtime.economyService)
            if (expansion.register()) {
                runtime.placeholderExpansion = expansion
                logger.info("PlaceholderAPI expansion registered.")
            }
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        return error.cause ?: error
    }
}

data class PluginRuntime(
    val settings: PluginSettings,
    val dataSource: AutoCloseable,
    val repository: JdbcEconomyRepository,
    val economyService: EconomyService,
    val migrationService: MigrationService,
    val redisSyncService: RedisBalanceSyncService?
) {
    var vaultHook: VaultHook? = null
    var placeholderExpansion: EcolinkPlaceholderExpansion? = null

    fun shutdown() {
        placeholderExpansion?.unregister()
        vaultHook?.close()
        redisSyncService?.close()
        dataSource.close()
    }
}
