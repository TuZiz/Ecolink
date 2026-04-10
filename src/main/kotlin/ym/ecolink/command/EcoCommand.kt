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
import ym.ecolink.economy.LedgerEntry
import ym.ecolink.migration.MigrationKind
import ym.ecolink.util.colorize
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

class EcoCommand(
    private val plugin: Ecolink
) : CommandExecutor, TabCompleter {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase()) {
            "balance" -> handleBalance(sender, args)
            "pay" -> handlePay(sender, args)
            "baltop" -> handleBaltop(sender, args)
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
            "pay" -> completeOnlinePlayers(args)
            "balance" -> {
                if (args.size == 1 && sender.hasPermission("ecolink.balance.others")) {
                    completeOnlinePlayers(args)
                } else {
                    mutableListOf()
                }
            }

            "baltop" -> mutableListOf()
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
                    plugin.replyLater(sender, "&eBalance: &6${runtime.settings.format(record.balance)}")
                }
            }
            return true
        }

        if (!sender.hasPermission("ecolink.balance.others")) {
            sender.sendMessage("&cYou do not have permission to view other balances.".colorize())
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
                    plugin.replyLater(sender, "&cAccount not found: &f${args[0]}")
                } else {
                    plugin.replyLater(sender, "&e${record.username}: &6${runtime.settings.format(record.balance)}")
                }
            }
        }
        return true
    }

    private fun handlePay(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val player = sender as? Player ?: return sendUsage(sender, "/pay <player> <amount>")
        if (!player.hasPermission("ecolink.pay")) {
            sender.sendMessage("&cYou do not have permission to pay.".colorize())
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
                    plugin.replyLater(sender, "&cAccount not found: &f${args[0]}")
                    return@complete
                }
                if (target.uuid == source.uuid) {
                    plugin.replyLater(sender, "&cYou cannot pay yourself.")
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
                            "&aPaid &f${receipt.to.username} &6${runtime.settings.format(amount)} &7(balance ${runtime.settings.format(receipt.from.balance)})"
                        )
                        Bukkit.getPlayer(receipt.to.uuid)?.takeIf { it.isOnline }?.let { targetPlayer ->
                            plugin.replyLater(
                                targetPlayer,
                                "&aYou received &6${runtime.settings.format(amount)} &afrom &f${player.name}&a."
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handleBaltop(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        if (!sender.hasPermission("ecolink.baltop")) {
            sender.sendMessage("&cYou do not have permission to use baltop.".colorize())
            return true
        }
        val page = parsePage(sender, args.getOrNull(0)) ?: return true
        runtime.economyService.getTopBalances(page).whenComplete { records, error ->
            complete(sender, runtime, error) {
                val startRank = (page - 1) * runtime.settings.feature.topPageSize + 1
                plugin.replyLater(sender, "&6Balance Top &7(page $page)")
                if (records.isEmpty()) {
                    plugin.replyLater(sender, "&7No account data on this page.")
                    return@complete
                }
                records.forEachIndexed { index, record ->
                    plugin.replyLater(
                        sender,
                        "&e#${startRank + index} &f${record.username} &7- &6${runtime.settings.format(record.balance)}"
                    )
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
            "ledger" -> handleLedger(sender, runtime, args)
            "help" -> {
                sender.sendMessage(helpLines(sender).joinToString("\n") { it.colorize() })
                true
            }

            else -> sendUsage(sender, "/ecolink <set|add|take|migrate|ledger|help>")
        }
    }

    private fun handleAdminMutation(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>,
        mutation: AdminMutation
    ): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            sender.sendMessage("&cYou do not have admin permission.".colorize())
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
                    plugin.replyLater(sender, "&cAccount not found: &f${args[1]}")
                    return@complete
                }
                val future = when (mutation) {
                    AdminMutation.SET -> runtime.economyService.setBalance(target, amount, sender.name, "admin-set")
                    AdminMutation.ADD -> runtime.economyService.addBalance(target, amount, sender.name, "admin-add")
                    AdminMutation.TAKE -> runtime.economyService.takeBalance(target, amount, sender.name, "admin-take")
                }
                future.whenComplete { record, mutationError ->
                    complete(sender, runtime, mutationError) {
                        val verb = when (mutation) {
                            AdminMutation.SET -> "set"
                            AdminMutation.ADD -> "added"
                            AdminMutation.TAKE -> "removed"
                        }
                        plugin.replyLater(
                            sender,
                            "&a${verb.replaceFirstChar { it.uppercase() }} &f${record.username}&a. New balance: &6${runtime.settings.format(record.balance)}"
                        )
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
            sender.sendMessage("&cYou do not have migration permission.".colorize())
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "/ecolink migrate <cmi|essentials|all> [overwrite]")
        }
        val kind = MigrationKind.from(args[1]) ?: run {
            sender.sendMessage("&cUnknown migration source: &f${args[1]}".colorize())
            return true
        }
        val overwrite = parseOverwrite(args.getOrNull(2))
        sender.sendMessage("&eMigration started in background...".colorize())
        runtime.migrationService.migrate(kind, overwrite).whenComplete { summary, error ->
            complete(sender, runtime, error) {
                plugin.replyLater(
                    sender,
                    "&aMigration complete: &f${summary.sourceLabel()} &7(found ${summary.discovered}, inserted ${summary.inserted}, updated ${summary.updated}, skipped ${summary.skipped}, failed ${summary.failed})"
                )
                summary.reports.forEach { report ->
                    plugin.replyLater(
                        sender,
                        "&7${report.source}: found ${report.discovered}, inserted ${report.inserted}, updated ${report.updated}, skipped ${report.skipped}, failed ${report.failed}"
                    )
                    report.sampleErrors.forEach { sample ->
                        plugin.replyLater(sender, "&8Sample error: $sample")
                    }
                }
            }
        }
        return true
    }

    private fun handleLedger(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.ledger")) {
            sender.sendMessage("&cYou do not have permission to view ledger entries.".colorize())
            return true
        }

        val selectorArg = args.getOrNull(1)
        val pageArg = args.getOrNull(2)
        val selfPlayer = sender as? Player

        val resolvedPage = when {
            selectorArg == null -> 1
            selectorArg.toIntOrNull() != null && selfPlayer != null -> selectorArg.toInt()
            else -> pageArg?.toIntOrNull() ?: 1
        }
        if (resolvedPage <= 0) {
            sender.sendMessage("&cPage must be greater than 0.".colorize())
            return true
        }

        val selfTarget = selfPlayer?.let { AccountIdentity(it.uniqueId, it.name) }
        val targetFuture: CompletableFuture<AccountIdentity?> = when {
            selectorArg == null -> CompletableFuture.completedFuture(selfTarget)
            selectorArg.toIntOrNull() != null && selfTarget != null -> CompletableFuture.completedFuture(selfTarget)
            else -> {
                if (!sender.hasPermission("ecolink.ledger.others")) {
                    sender.sendMessage("&cYou do not have permission to view other ledgers.".colorize())
                    return true
                }
                resolveTarget(runtime, selectorArg)
            }
        }

        targetFuture.whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&cAccount not found.")
                    return@complete
                }
                runtime.economyService.getLedger(target, resolvedPage).whenComplete { entries, ledgerError ->
                    complete(sender, runtime, ledgerError) {
                        plugin.replyLater(sender, "&6Ledger &7${target.username} &7(page $resolvedPage)")
                        if (entries.isEmpty()) {
                            plugin.replyLater(sender, "&7No ledger entries on this page.")
                            return@complete
                        }
                        entries.forEach { entry ->
                            plugin.replyLater(sender, formatLedgerLine(runtime, entry))
                        }
                    }
                }
            }
        }
        return true
    }

    private fun formatLedgerLine(runtime: PluginRuntime, entry: LedgerEntry): String {
        val time = timeFormatter.format(entry.createdAt)
        val reason = entry.reason?.takeIf { it.isNotBlank() } ?: "n/a"
        return "&7[$time] &e${entry.action.name} &f${runtime.settings.format(entry.amount)} &7-> &6${runtime.settings.format(entry.balanceAfter)} &8(${entry.sourceServer}, $reason)"
    }

    private fun resolveTarget(runtime: PluginRuntime, selector: String): CompletableFuture<AccountIdentity?> {
        Bukkit.getPlayerExact(selector)?.let { online ->
            return CompletableFuture.completedFuture(AccountIdentity(online.uniqueId, online.name))
        }
        return runtime.economyService.resolveIdentity(selector)
    }

    private fun requireReady(sender: CommandSender): PluginRuntime? {
        plugin.runtimeOrNull()?.let { return it }
        plugin.startupErrorOrNull()?.let {
            sender.sendMessage("&cEcolink startup failed: ${it.message ?: it.javaClass.simpleName}".colorize())
            return null
        }
        sender.sendMessage("&eEcolink is still booting, please retry in a moment.".colorize())
        return null
    }

    private fun parsePositiveAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String
    ): BigDecimal? = parseAmount(runtime, sender, raw, allowZero = false)

    private fun parseNonNegativeAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String
    ): BigDecimal? = parseAmount(runtime, sender, raw, allowZero = true)

    private fun parseAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String,
        allowZero: Boolean
    ): BigDecimal? {
        val amount = runCatching { runtime.settings.normalize(BigDecimal(raw)) }.getOrNull()
        if (amount == null) {
            sender.sendMessage("&cInvalid amount: &f$raw".colorize())
            return null
        }
        val valid = if (allowZero) amount.signum() >= 0 else amount.signum() > 0
        if (!valid) {
            sender.sendMessage("&cAmount must be ${if (allowZero) ">= 0" else "> 0"}.".colorize())
            return null
        }
        return amount
    }

    private fun parsePage(sender: CommandSender, raw: String?): Int? {
        if (raw == null) {
            return 1
        }
        val page = raw.toIntOrNull()
        if (page == null || page <= 0) {
            sender.sendMessage("&cInvalid page: &f$raw".colorize())
            return null
        }
        return page
    }

    private fun parseOverwrite(raw: String?): Boolean {
        return when (raw?.lowercase()) {
            "true", "yes", "y", "1", "overwrite", "force" -> true
            else -> false
        }
    }

    private fun sendUsage(sender: CommandSender, usage: String): Boolean {
        sender.sendMessage("&eUsage: &f$usage".colorize())
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
                plugin.replyLater(sender, "&cInsufficient funds. Balance: &f${runtime.settings.format(cause.currentBalance)}")
            }

            else -> {
                plugin.logger.log(Level.SEVERE, "Ecolink command failed.", cause)
                plugin.replyLater(sender, "&cOperation failed: ${cause.message ?: cause.javaClass.simpleName}")
            }
        }
    }

    private fun helpLines(sender: CommandSender): List<String> {
        val lines = mutableListOf(
            "&6Ecolink Commands",
            "&e/balance [player] &7- view balances",
            "&e/pay <player> <amount> &7- transfer money",
            "&e/baltop [page] &7- view richest accounts",
            "&e/ecolink ledger [player] [page] &7- view transaction history"
        )
        if (sender.hasPermission("ecolink.admin")) {
            lines += "&e/ecolink set <player> <amount> &7- set balance"
            lines += "&e/ecolink add <player> <amount> &7- add balance"
            lines += "&e/ecolink take <player> <amount> &7- take balance"
        }
        if (sender.hasPermission("ecolink.migrate")) {
            lines += "&e/ecolink migrate <cmi|essentials|all> [overwrite] &7- import old data"
        }
        return lines
    }

    private fun completeRoot(sender: CommandSender, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("help", "ledger")
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
        if (args.size == 2 && args[0].lowercase() in listOf("set", "add", "take", "ledger")) {
            return completeOnlinePlayers(arrayOf(args[1]))
        }
        return mutableListOf()
    }

    private fun completeOnlinePlayers(args: Array<out String>): MutableList<String> {
        if (args.size != 1) {
            return mutableListOf()
        }
        return Bukkit.getOnlinePlayers().map { it.name }.filter {
            it.startsWith(args[0], ignoreCase = true)
        }.toMutableList()
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
