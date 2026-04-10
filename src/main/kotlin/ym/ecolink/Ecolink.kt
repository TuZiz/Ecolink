package ym.ecolink

import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.command.EcoCommand
import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.EconomyService
import ym.ecolink.listener.PlayerLifecycleListener
import ym.ecolink.migration.MigrationService
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.storage.EcolinkDataSourceFactory
import ym.ecolink.storage.JdbcEconomyRepository
import ym.ecolink.util.colorize
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

class Ecolink : JavaPlugin() {

    lateinit var bootstrapSettings: PluginSettings
        private set

    lateinit var dispatcher: ServerTaskDispatcher
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
            logger.info(
                "Ecolink initialized. storage=${created.settings.storage.type.id}, " +
                    "serverId=${created.settings.serverId}"
            )
        }
    }

    override fun onDisable() {
        runtime?.shutdown()
        dispatcher.shutdown()
    }

    fun runtimeOrNull(): PluginRuntime? = runtime

    fun startupErrorOrNull(): Throwable? = startupFailure

    fun replyLater(sender: CommandSender, message: String) {
        dispatcher.runOnSender(sender) {
            sender.sendMessage(message.colorize())
        }
    }

    private fun bootstrap(settings: PluginSettings): PluginRuntime {
        val dataSource = EcolinkDataSourceFactory.create(settings.storage)
        val repository = JdbcEconomyRepository(
            dataSource = dataSource,
            tablePrefix = settings.storage.tablePrefix,
            scale = settings.balanceScale
        )
        repository.initialize()
        val economyService = EconomyService(settings, repository, dispatcher)
        val migrationService = MigrationService(settings, repository, dispatcher)
        return PluginRuntime(settings, dataSource, repository, economyService, migrationService)
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
    val migrationService: MigrationService
) {
    fun shutdown() {
        dataSource.close()
    }
}
