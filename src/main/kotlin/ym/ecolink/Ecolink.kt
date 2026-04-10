package ym.ecolink

import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.command.EcoCommand
import ym.ecolink.config.PluginConfigStore
import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.EconomyService
import ym.ecolink.hook.papi.EcolinkPlaceholderExpansion
import ym.ecolink.hook.vault.VaultHook
import ym.ecolink.i18n.MessagePlaceholder
import ym.ecolink.i18n.MessageService
import ym.ecolink.listener.PlayerLifecycleListener
import ym.ecolink.migration.MigrationService
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.shop.ShopService
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

    lateinit var configStore: PluginConfigStore
        private set

    @Volatile
    private var runtime: PluginRuntime? = null

    @Volatile
    private var startupFailure: Throwable? = null

    private val runtimeLock = Any()

    override fun onEnable() {
        saveDefaultConfig()
        configStore = PluginConfigStore(this)
        bootstrapSettings = configStore.loadSettings()
        messages = MessageService(this, bootstrapSettings.language).also { it.initialize() }
        dispatcher = ServerTaskDispatcher(this, bootstrapSettings.workerThreads)

        val commandHandler = EcoCommand(this)
        bindCommand("el", commandHandler)
        bindCommand("money", commandHandler)
        bindCommand("balance", commandHandler)
        bindCommand("pay", commandHandler)
        bindCommand("baltop", commandHandler)

        server.pluginManager.registerEvents(PlayerLifecycleListener(this), this)

        startupFuture = dispatcher.supplyAsync {
            synchronized(runtimeLock) {
                bootstrap(bootstrapSettings).also { created ->
                    installBridgesBlocking(created)
                    runtime = created
                    startupFailure = null
                }
            }
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

    fun activeSettings(): PluginSettings = runtime?.settings ?: bootstrapSettings

    fun reloadRuntime(): CompletableFuture<PluginRuntime> {
        return dispatcher.supplyAsync {
            synchronized(runtimeLock) {
                val newSettings = configStore.loadSettings()
                replaceRuntime(newSettings)
            }
        }
    }

    fun updateEnabledCurrencies(enabled: Collection<String>): CompletableFuture<PluginRuntime> {
        return dispatcher.supplyAsync {
            synchronized(runtimeLock) {
                val newSettings = configStore.updateEnabledCurrencies(enabled)
                replaceRuntime(newSettings)
            }
        }
    }

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

    private fun bindCommand(name: String, commandHandler: EcoCommand) {
        getCommand(name)?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        }
    }

    private fun replaceRuntime(settings: PluginSettings): PluginRuntime {
        val previousRuntime = runtime
        val previousMessages = messages
        val newMessages = MessageService(this, settings.language).also { it.initialize() }
        return try {
            val newRuntime = bootstrap(settings)
            detachBridgesBlocking(previousRuntime)
            installBridgesBlocking(newRuntime)
            bootstrapSettings = settings
            runtime = newRuntime
            startupFuture = CompletableFuture.completedFuture(newRuntime)
            startupFailure = null
            messages = newMessages
            previousRuntime?.shutdownCore()
            previousMessages.close()
            newRuntime
        } catch (error: Throwable) {
            newMessages.close()
            if (previousRuntime != null) {
                runCatching {
                    installBridgesBlocking(previousRuntime)
                }.onFailure { bridgeError ->
                    logger.log(Level.SEVERE, "Failed to restore previous bridges after reload failure.", bridgeError)
                }
            }
            throw error
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
        val shopService = ShopService(this, settings, economyService, dispatcher)
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
            shopService = shopService,
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

    private fun installBridgesBlocking(runtime: PluginRuntime) {
        runOnMainAndWait {
            installOptionalBridges(runtime)
        }
    }

    private fun detachBridgesBlocking(runtime: PluginRuntime?) {
        if (runtime == null) {
            return
        }
        runOnMainAndWait {
            runtime.detachPlatformHooks()
        }
    }

    private fun runOnMainAndWait(action: () -> Unit) {
        val future = CompletableFuture<Unit>()
        dispatcher.runGlobal {
            try {
                action()
                future.complete(Unit)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        future.join()
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
    val shopService: ShopService,
    val redisSyncService: RedisBalanceSyncService?
) {
    var vaultHook: VaultHook? = null
    var placeholderExpansion: EcolinkPlaceholderExpansion? = null

    fun detachPlatformHooks() {
        placeholderExpansion?.unregister()
        placeholderExpansion = null
        vaultHook?.close()
        vaultHook = null
    }

    fun shutdownCore() {
        redisSyncService?.close()
        dataSource.close()
    }

    fun shutdown() {
        detachPlatformHooks()
        shutdownCore()
    }
}
