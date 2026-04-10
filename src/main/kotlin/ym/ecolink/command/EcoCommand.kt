package ym.ecolink.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ym.ecolink.Ecolink
import ym.ecolink.PluginRuntime
import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.InsufficientFundsException
import ym.ecolink.migration.MigrationKind
import ym.ecolink.util.colorize
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

class EcoCommand(
    private val plugin: Ecolink
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase()) {
            "balance" -> handleBalance(sender, args)
            "pay" -> handlePay(sender, args)
            else -> handleRoot(sender, args)
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        return when (command.name.lowercase()) {
            "pay" -> {
                if (args.size == 1) {
                    Bukkit.getOnlinePlayers().map { it.name }.filter {
                        it.startsWith(args[0], ignoreCase = true)
                    }.toMutableList()
                } else {
                    mutableListOf()
                }
            }

            "balance" -> {
                if (args.size == 1 && sender.hasPermission("ecolink.balance.others")) {
                    Bukkit.getOnlinePlayers().map { it.name }.filter {
                        it.startsWith(args[0], ignoreCase = true)
                    }.toMutableList()
                } else {
                    mutableListOf()
                }
            }

            else -> completeRoot(sender, args)
        }
    }

    private fun handleBalance(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        if (args.isEmpty()) {
            val player = sender as? Player ?: return sendUsage(sender, "/balance <player>")
            val identity = AccountIdentity(player.uniqueId, player.name)
            runtime.economyService.getBalance(identity).whenComplete { record, error ->
                complete(sender, runtime, error) {
                    plugin.replyLater(
                        sender,
                        "&e你的余额: &6${runtime.settings.format(record.balance)}"
                    )
                }
            }
            return true
        }

        if (!sender.hasPermission("ecolink.balance.others")) {
            sender.sendMessage("&c你没有查看他人余额的权限。".colorize())
            return true
        }

        resolveTarget(runtime, args[0]).thenCompose { identity ->
            if (identity == null) {
                CompletableFuture.completedFuture(null)
            } else {
                runtime.economyService.getBalance(identity).thenApply { it }
            }
        }.whenComplete { record, error ->
            complete(sender, runtime, error) {
                if (record == null) {
                    plugin.replyLater(sender, "&c找不到目标账户: &f${args[0]}")
                } else {
                    plugin.replyLater(
                        sender,
                        "&e${record.username} 的余额: &6${runtime.settings.format(record.balance)}"
                    )
                }
            }
        }
        return true
    }

    private fun handlePay(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val player = sender as? Player ?: return sendUsage(sender, "/pay <player> <amount>")
        if (!player.hasPermission("ecolink.pay")) {
            sender.sendMessage("&c你没有执行转账的权限。".colorize())
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "/pay <player> <amount>")
        }

        val amount = parsePositiveAmount(runtime, sender, args[1]) ?: return true
        val source = AccountIdentity(player.uniqueId, player.name)

        resolveTarget(runtime, args[0]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&c找不到目标账户: &f${args[0]}")
                    return@complete
                }
                if (target.uuid == source.uuid) {
                    plugin.replyLater(sender, "&c不能给自己转账。")
                    return@complete
                }
                runtime.economyService.transfer(
                    source = source,
                    target = target,
                    amount = amount,
                    actor = player.name,
                    reason = "player-pay"
                ).whenComplete { receipt, payError ->
                    complete(sender, runtime, payError) {
                        plugin.replyLater(
                            sender,
                            "&a已向 &f${receipt.to.username} &a转账 &6${runtime.settings.format(amount)}" +
                                " &7(剩余 ${runtime.settings.format(receipt.from.balance)})"
                        )
                        val targetPlayer = Bukkit.getPlayer(receipt.to.uuid)
                        if (targetPlayer != null && targetPlayer.isOnline) {
                            plugin.replyLater(
                                targetPlayer,
                                "&a你收到了来自 &f${player.name} &a的转账: &6${runtime.settings.format(amount)}"
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handleRoot(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(helpLines(sender).joinToString("\n") { it.colorize() })
            return true
        }

        val runtime = requireReady(sender) ?: return true
        return when (args[0].lowercase()) {
            "set" -> handleAdminMutation(sender, runtime, args, AdminMutation.SET)
            "add" -> handleAdminMutation(sender, runtime, args, AdminMutation.ADD)
            "take" -> handleAdminMutation(sender, runtime, args, AdminMutation.TAKE)
            "migrate" -> handleMigration(sender, runtime, args)
            "help" -> {
                sender.sendMessage(helpLines(sender).joinToString("\n") { it.colorize() })
                true
            }

            else -> sendUsage(sender, "/ecolink <set|add|take|migrate|help>")
        }
    }

    private fun handleAdminMutation(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>,
        mutation: AdminMutation
    ): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            sender.sendMessage("&c你没有管理权限。".colorize())
            return true
        }
        if (args.size < 3) {
            return sendUsage(sender, "/ecolink ${mutation.keyword} <player> <amount>")
        }

        val amount = when (mutation) {
            AdminMutation.SET -> parseNonNegativeAmount(runtime, sender, args[2])
            else -> parsePositiveAmount(runtime, sender, args[2])
        } ?: return true

        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&c找不到目标账户: &f${args[1]}")
                    return@complete
                }
                val future = when (mutation) {
                    AdminMutation.SET -> runtime.economyService.setBalance(
                        target = target,
                        amount = amount,
                        actor = sender.name,
                        reason = "admin-set"
                    )

                    AdminMutation.ADD -> runtime.economyService.addBalance(
                        target = target,
                        amount = amount,
                        actor = sender.name,
                        reason = "admin-add"
                    )

                    AdminMutation.TAKE -> runtime.economyService.takeBalance(
                        target = target,
                        amount = amount,
                        actor = sender.name,
                        reason = "admin-take"
                    )
                }
                future.whenComplete { record, mutationError ->
                    complete(sender, runtime, mutationError) {
                        val verb = when (mutation) {
                            AdminMutation.SET -> "设置"
                            AdminMutation.ADD -> "增加"
                            AdminMutation.TAKE -> "扣除"
                        }
                        plugin.replyLater(
                            sender,
                            "&a已为 &f${record.username} &a${verb}余额，当前余额: &6${runtime.settings.format(record.balance)}"
                        )
                        val targetPlayer = Bukkit.getPlayer(record.uuid)
                        if (targetPlayer != null && targetPlayer.isOnline && targetPlayer.name != sender.name) {
                            plugin.replyLater(
                                targetPlayer,
                                "&e你的余额已被管理员调整，当前余额: &6${runtime.settings.format(record.balance)}"
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handleMigration(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("ecolink.migrate")) {
            sender.sendMessage("&c你没有执行迁移的权限。".colorize())
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "/ecolink migrate <cmi|essentials|all> [overwrite]")
        }
        val kind = MigrationKind.from(args[1])
        if (kind == null) {
            sender.sendMessage("&c未知迁移源: &f${args[1]}".colorize())
            return true
        }
        val overwrite = parseOverwrite(args.getOrNull(2))
        sender.sendMessage("&e正在后台执行迁移，请稍候...".colorize())
        runtime.migrationService.migrate(kind, overwrite).whenComplete { summary, error ->
            complete(sender, runtime, error) {
                plugin.replyLater(
                    sender,
                    "&a迁移完成: &f${summary.sourceLabel()} &7(发现 ${summary.discovered}，新增 ${summary.inserted}，覆盖 ${summary.updated}，跳过 ${summary.skipped}，失败 ${summary.failed})"
                )
                summary.reports.forEach { report ->
                    plugin.replyLater(
                        sender,
                        "&7- ${report.source}: 发现 ${report.discovered}，新增 ${report.inserted}，覆盖 ${report.updated}，跳过 ${report.skipped}，失败 ${report.failed}"
                    )
                    report.sampleErrors.forEach { sample ->
                        plugin.replyLater(sender, "&8错误样例: $sample")
                    }
                }
            }
        }
        return true
    }

    private fun resolveTarget(
        runtime: PluginRuntime,
        selector: String
    ): CompletableFuture<AccountIdentity?> {
        Bukkit.getPlayerExact(selector)?.let { online ->
            return CompletableFuture.completedFuture(AccountIdentity(online.uniqueId, online.name))
        }
        return runtime.economyService.resolveIdentity(selector)
    }

    private fun requireReady(sender: CommandSender): PluginRuntime? {
        plugin.runtimeOrNull()?.let { return it }
        plugin.startupErrorOrNull()?.let {
            sender.sendMessage("&cEcolink 初始化失败: ${it.message ?: it.javaClass.simpleName}".colorize())
            return null
        }
        sender.sendMessage("&eEcolink 正在初始化数据库，请稍后再试。".colorize())
        return null
    }

    private fun parsePositiveAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String
    ): BigDecimal? {
        return parseAmount(runtime, sender, raw, allowZero = false)
    }

    private fun parseNonNegativeAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String
    ): BigDecimal? {
        return parseAmount(runtime, sender, raw, allowZero = true)
    }

    private fun parseAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String,
        allowZero: Boolean
    ): BigDecimal? {
        val amount = runCatching { runtime.settings.normalize(BigDecimal(raw)) }.getOrNull()
        if (amount == null) {
            sender.sendMessage("&c金额格式无效: &f$raw".colorize())
            return null
        }
        val valid = if (allowZero) amount.signum() >= 0 else amount.signum() > 0
        if (!valid) {
            sender.sendMessage("&c金额必须${if (allowZero) "大于等于" else "大于"} 0。".colorize())
            return null
        }
        return amount
    }

    private fun parseOverwrite(raw: String?): Boolean {
        return when (raw?.lowercase()) {
            "true", "yes", "y", "1", "overwrite", "force" -> true
            else -> false
        }
    }

    private fun sendUsage(sender: CommandSender, usage: String): Boolean {
        sender.sendMessage("&e用法: &f$usage".colorize())
        return true
    }

    private fun complete(
        sender: CommandSender,
        runtime: PluginRuntime,
        error: Throwable?,
        success: () -> Unit
    ) {
        if (error == null) {
            success()
            return
        }
        val cause = unwrap(error)
        when (cause) {
            is InsufficientFundsException -> {
                plugin.replyLater(
                    sender,
                    "&c余额不足。当前余额: &f${runtime.settings.format(cause.currentBalance)}"
                )
            }

            else -> {
                plugin.logger.log(Level.SEVERE, "Ecolink command failed.", cause)
                plugin.replyLater(
                    sender,
                    "&c操作失败: ${cause.message ?: cause.javaClass.simpleName}"
                )
            }
        }
    }

    private fun helpLines(sender: CommandSender): List<String> {
        val lines = mutableListOf(
            "&6Ecolink 命令",
            "&e/balance [player] &7- 查询余额",
            "&e/pay <player> <amount> &7- 给其他玩家转账"
        )
        if (sender.hasPermission("ecolink.admin")) {
            lines += "&e/ecolink set <player> <amount> &7- 直接设置余额"
            lines += "&e/ecolink add <player> <amount> &7- 增加余额"
            lines += "&e/ecolink take <player> <amount> &7- 扣除余额"
        }
        if (sender.hasPermission("ecolink.migrate")) {
            lines += "&e/ecolink migrate <cmi|essentials|all> [overwrite] &7- 一键导入旧经济数据"
        }
        return lines
    }

    private fun completeRoot(sender: CommandSender, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("help")
            if (sender.hasPermission("ecolink.admin")) {
                options += listOf("set", "add", "take")
            }
            if (sender.hasPermission("ecolink.migrate")) {
                options += "migrate"
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        if (args.size == 2 && args[0].equals("migrate", ignoreCase = true)) {
            return listOf("cmi", "essentials", "all").filter {
                it.startsWith(args[1], ignoreCase = true)
            }.toMutableList()
        }
        if (args.size == 3 && args[0].equals("migrate", ignoreCase = true)) {
            return listOf("overwrite").filter {
                it.startsWith(args[2], ignoreCase = true)
            }.toMutableList()
        }
        if (args.size == 2 && args[0].lowercase() in listOf("set", "add", "take")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter {
                it.startsWith(args[1], ignoreCase = true)
            }.toMutableList()
        }
        return mutableListOf()
    }

    private fun unwrap(error: Throwable): Throwable {
        return error.cause ?: error
    }
}

private enum class AdminMutation(val keyword: String) {
    SET("set"),
    ADD("add"),
    TAKE("take")
}
