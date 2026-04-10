package ym.ecolink.platform

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.max

class ServerTaskDispatcher(
    private val plugin: JavaPlugin,
    workerThreads: Int
) {

    private val workerPool: ExecutorService = Executors.newFixedThreadPool(
        max(2, workerThreads),
        NamedThreadFactory("ecolink-worker")
    )
    private val foliaEnabled = runCatching {
        Bukkit::class.java.getMethod("getGlobalRegionScheduler")
    }.isSuccess

    fun <T> supplyAsync(block: () -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(Supplier(block), workerPool)
    }

    fun runOnSender(sender: CommandSender, block: () -> Unit) {
        if (sender is Player) {
            runOnPlayer(sender, block)
        } else {
            runGlobal(block)
        }
    }

    fun runGlobal(block: () -> Unit) {
        if (foliaEnabled && tryRunGlobal(block)) {
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    fun runOnPlayer(player: Player, block: () -> Unit) {
        if (foliaEnabled && tryRunEntity(player, block)) {
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (player.isOnline) {
                block()
            }
        })
    }

    fun shutdown() {
        workerPool.shutdown()
        if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
            workerPool.shutdownNow()
        }
    }

    private fun tryRunGlobal(block: () -> Unit): Boolean {
        return runCatching {
            val scheduler = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null) ?: return false
            val execute = scheduler.javaClass.methods.firstOrNull {
                it.name == "execute" && it.parameterCount == 2
            }
            if (execute != null) {
                execute.invoke(scheduler, plugin, Runnable(block))
                return true
            }
            val run = scheduler.javaClass.methods.firstOrNull {
                it.name == "run" && it.parameterCount == 2
            }
            if (run != null) {
                run.invoke(scheduler, plugin, Consumer<Any?> { block() })
                return true
            }
            false
        }.getOrDefault(false)
    }

    private fun tryRunEntity(player: Player, block: () -> Unit): Boolean {
        return runCatching {
            val getScheduler = player.javaClass.methods.firstOrNull {
                it.name == "getScheduler" && it.parameterCount == 0
            } ?: return false
            val scheduler = getScheduler.invoke(player) ?: return false

            val execute = scheduler.javaClass.methods.firstOrNull {
                it.name == "execute" && it.parameterCount == 4
            }
            if (execute != null) {
                val result = execute.invoke(scheduler, plugin, Runnable(block), null, 1L)
                return result !is Boolean || result
            }

            val run = scheduler.javaClass.methods.firstOrNull {
                it.name == "run" && it.parameterCount == 3
            }
            if (run != null) {
                run.invoke(scheduler, plugin, Consumer<Any?> { block() }, null)
                return true
            }
            false
        }.getOrDefault(false)
    }

    private class NamedThreadFactory(
        private val prefix: String
    ) : java.util.concurrent.ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}
