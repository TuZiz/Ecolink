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
import ym.ecolink.economy.RechargeReceipt
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
            "pay" -> completePay(args)
            "balance" -> completeBalance(sender, args)
            "baltop" -> completeBaltop(args)
            else -> completeRoot(sender, args)
        }
    }

    private fun handleBalance(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val selfPlayer = sender as? Player

        if (args.isEmpty()) {
            val player = selfPlayer ?: return sendUsage(sender, "/balance <player> [currency]")
            return queryBalance(sender, runtime, AccountIdentity(player.uniqueId, player.name), runtime.settings.defaultCurrencyKey, selfView = true)
        }

        if (selfPlayer != null && args.size == 1) {
            runtime.settings.findCurrency(args[0])?.let { currency ->
                return queryBalance(sender, runtime, AccountIdentity(selfPlayer.uniqueId, selfPlayer.name), currency.key, selfView = true)
            }
        }

        if (!sender.hasPermission("ecolink.balance.others")) {
            sender.sendMessage("&cYou do not have permission to view other balances.".colorize())
            return true
        }

        val currencyKey = parseCurrency(runtime, sender, args.getOrNull(1), runtime.settings.defaultCurrencyKey) ?: return true
        resolveTarget(runtime, args[0]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&cAccount not found: &f${args[0]}")
                    return@complete
                }
                queryBalance(sender, runtime, target, currencyKey, selfView = false)
            }
        }
        return true
    }

    private fun queryBalance(
        sender: CommandSender,
        runtime: PluginRuntime,
        target: AccountIdentity,
        currencyKey: String,
        selfView: Boolean
    ): Boolean {
        val currency = runtime.settings.requireCurrency(currencyKey)
        runtime.economyService.getBalance(target, currency.key).whenComplete { record, error ->
            complete(sender, runtime, error) {
                val prefix = if (selfView) {
                    "&eBalance &7(${currency.displayName}): "
                } else {
                    "&e${record.username} &7(${currency.displayName}): "
                }
                plugin.replyLater(sender, prefix + "&6${runtime.settings.format(currency.key, record.balance)}")
            }
        }
        return true
    }

    private fun handlePay(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val player = sender as? Player ?: return sendUsage(sender, "/pay <player> <amount> [currency]")
        if (!player.hasPermission("ecolink.pay")) {
            sender.sendMessage("&cYou do not have permission to pay.".colorize())
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "/pay <player> <amount> [currency]")
        }

        val currencyKey = parseCurrency(runtime, sender, args.getOrNull(2), runtime.settings.defaultCurrencyKey) ?: return true
        val currency = runtime.settings.requireCurrency(currencyKey)
        if (!currency.transferable) {
            sender.sendMessage("&cCurrency ${currency.displayName} cannot be transferred.".colorize())
            return true
        }
        val amount = parsePositiveAmount(runtime, sender, args[1], currency.key) ?: return true
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
                    currencyKey = currency.key,
                    amount = amount,
                    actor = player.name,
                    reason = "player-pay"
                ).whenComplete { receipt, payError ->
                    complete(sender, runtime, payError) {
                        plugin.replyLater(
                            sender,
                            "&aPaid &f${receipt.to.username} &6${runtime.settings.format(currency.key, amount)} " +
                                "&7(balance ${runtime.settings.format(currency.key, receipt.from.balance)})"
                        )
                        Bukkit.getPlayer(receipt.to.uuid)?.takeIf { it.isOnline }?.let { targetPlayer ->
                            plugin.replyLater(
                                targetPlayer,
                                "&aYou received &6${runtime.settings.format(currency.key, amount)} &afrom &f${player.name}&a."
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
        val first = args.getOrNull(0)
        val second = args.getOrNull(1)
        val currencyKey = if (first != null && first.toIntOrNull() == null) {
            parseCurrency(runtime, sender, first, runtime.settings.defaultCurrencyKey) ?: return true
        } else {
            runtime.settings.defaultCurrencyKey
        }
        val pageRaw = if (first != null && first.toIntOrNull() != null) first else second
        val page = parsePage(sender, pageRaw) ?: return true

        runtime.economyService.getTopBalances(page, currencyKey).whenComplete { records, error ->
            complete(sender, runtime, error) {
                val currency = runtime.settings.requireCurrency(currencyKey)
                val startRank = (page - 1) * runtime.settings.feature.topPageSize + 1
                plugin.replyLater(sender, "&6Balance Top &7(${currency.displayName}, page $page)")
                if (records.isEmpty()) {
                    plugin.replyLater(sender, "&7No account data on this page.")
                    return@complete
                }
                records.forEachIndexed { index, record ->
                    plugin.replyLater(
                        sender,
                        "&e#${startRank + index} &f${record.username} &7- &6${runtime.settings.format(currency.key, record.balance)}"
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
            "recharge" -> handleRecharge(sender, runtime, args)
            "migrate" -> handleMigration(sender, runtime, args)
            "ledger" -> handleLedger(sender, runtime, args)
            "currencies" -> handleCurrencies(sender, runtime)
            "help" -> {
                sender.sendMessage(helpLines(sender).joinToString("\n") { it.colorize() })
                true
            }

            else -> sendUsage(sender, "/ecolink <set|add|take|recharge|migrate|ledger|currencies|help>")
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
            return sendUsage(sender, "/ecolink ${mutation.keyword} <player> <amount> [currency]")
        }

        val currencyKey = parseCurrency(runtime, sender, args.getOrNull(3), runtime.settings.defaultCurrencyKey) ?: return true
        val amount = when (mutation) {
            AdminMutation.SET -> parseNonNegativeAmount(runtime, sender, args[2], currencyKey)
            else -> parsePositiveAmount(runtime, sender, args[2], currencyKey)
        } ?: return true

        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&cAccount not found: &f${args[1]}")
                    return@complete
                }
                val future = when (mutation) {
                    AdminMutation.SET -> runtime.economyService.setBalance(target, currencyKey, amount, sender.name, "admin-set")
                    AdminMutation.ADD -> runtime.economyService.addBalance(target, currencyKey, amount, sender.name, "admin-add")
                    AdminMutation.TAKE -> runtime.economyService.takeBalance(target, currencyKey, amount, sender.name, "admin-take")
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
                            "&a${verb.replaceFirstChar { it.uppercase() }} &f${record.username}&a. " +
                                "New balance: &6${runtime.settings.format(currencyKey, record.balance)}"
                        )
                    }
                }
            }
        }
        return true
    }

    private fun handleRecharge(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.recharge")) {
            sender.sendMessage("&cYou do not have recharge permission.".colorize())
            return true
        }
        if (args.size < 5) {
            return sendUsage(sender, "/ecolink recharge <player> <currency> <amount> <transactionId> [reason...]")
        }

        val currencyKey = parseCurrency(runtime, sender, args[2], null) ?: return true
        val amount = parsePositiveAmount(runtime, sender, args[3], currencyKey) ?: return true
        val transactionId = args[4].trim()
        if (transactionId.isBlank()) {
            sender.sendMessage("&cTransaction id cannot be blank.".colorize())
            return true
        }
        val reason = if (args.size > 5) args.copyOfRange(5, args.size).joinToString(" ") else "manual-recharge"

        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&cAccount not found: &f${args[1]}")
                    return@complete
                }
                runtime.economyService.recharge(
                    target = target,
                    currencyKey = currencyKey,
                    transactionId = transactionId,
                    amount = amount,
                    actor = sender.name,
                    reason = reason
                ).whenComplete { receipt, rechargeError ->
                    complete(sender, runtime, rechargeError) {
                        plugin.replyLater(sender, rechargeMessage(runtime, receipt))
                    }
                }
            }
        }
        return true
    }

    private fun rechargeMessage(runtime: PluginRuntime, receipt: RechargeReceipt): String {
        val state = if (receipt.duplicate) "&eReplay accepted" else "&aRecharge applied"
        return "$state &7(tx=${receipt.transactionId}) &f${receipt.record.username} &7-> " +
            "&6${runtime.settings.format(receipt.record.currencyKey, receipt.amount)} " +
            "&7(balance ${runtime.settings.format(receipt.record.currencyKey, receipt.record.balance)})"
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

        val selfPlayer = sender as? Player
        var selector: String? = null
        var currencyRaw: String? = null
        var pageRaw: String? = null

        when (val first = args.getOrNull(1)) {
            null -> Unit
            else -> {
                when {
                    selfPlayer != null && first.toIntOrNull() != null -> pageRaw = first
                    selfPlayer != null && runtime.settings.findCurrency(first) != null -> {
                        currencyRaw = first
                        pageRaw = args.getOrNull(2)
                    }

                    else -> {
                        selector = first
                        val second = args.getOrNull(2)
                        if (second != null && second.toIntOrNull() != null) {
                            pageRaw = second
                        } else {
                            currencyRaw = second
                            pageRaw = args.getOrNull(3)
                        }
                    }
                }
            }
        }

        val page = parsePage(sender, pageRaw) ?: return true
        val currencyKey = parseCurrency(runtime, sender, currencyRaw, runtime.settings.defaultCurrencyKey) ?: return true

        val targetFuture: CompletableFuture<AccountIdentity?> = if (selector == null) {
            val player = selfPlayer ?: return sendUsage(sender, "/ecolink ledger <player> [currency] [page]")
            CompletableFuture.completedFuture(AccountIdentity(player.uniqueId, player.name))
        } else {
            if (!sender.hasPermission("ecolink.ledger.others")) {
                sender.sendMessage("&cYou do not have permission to view other ledgers.".colorize())
                return true
            }
            resolveTarget(runtime, selector)
        }

        targetFuture.whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "&cAccount not found.")
                    return@complete
                }
                runtime.economyService.getLedger(target, page, currencyKey).whenComplete { entries, ledgerError ->
                    complete(sender, runtime, ledgerError) {
                        val currency = runtime.settings.requireCurrency(currencyKey)
                        plugin.replyLater(sender, "&6Ledger &7${target.username} &7(${currency.displayName}, page $page)")
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

    private fun handleCurrencies(sender: CommandSender, runtime: PluginRuntime): Boolean {
        plugin.replyLater(sender, "&6Currencies")
        runtime.settings.listCurrencies().forEach { currency ->
            val tags = buildList {
                if (currency.key == runtime.settings.defaultCurrencyKey) add("default")
                if (currency.vaultPrimary) add("vault")
                if (!currency.transferable) add("no-pay")
            }
            val suffix = if (tags.isEmpty()) "" else " &8[${tags.joinToString(", ")}]"
            plugin.replyLater(
                sender,
                "&e${currency.key} &7(${currency.displayName}) &7start=&f${runtime.settings.format(currency.key, currency.startingBalance)}$suffix"
            )
        }
        return true
    }

    private fun formatLedgerLine(runtime: PluginRuntime, entry: LedgerEntry): String {
        val time = timeFormatter.format(entry.createdAt)
        val reason = entry.reason?.takeIf { it.isNotBlank() } ?: "n/a"
        val transaction = entry.idempotencyKey?.let { ", tx=$it" } ?: ""
        return "&7[$time] &e${entry.action.name} &f${runtime.settings.format(entry.currencyKey, entry.amount)} " +
            "&7-> &6${runtime.settings.format(entry.currencyKey, entry.balanceAfter)} " +
            "&8(${entry.sourceServer}, $reason$transaction)"
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
        raw: String,
        currencyKey: String
    ): BigDecimal? = parseAmount(runtime, sender, raw, currencyKey, allowZero = false)

    private fun parseNonNegativeAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String,
        currencyKey: String
    ): BigDecimal? = parseAmount(runtime, sender, raw, currencyKey, allowZero = true)

    private fun parseAmount(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String,
        currencyKey: String,
        allowZero: Boolean
    ): BigDecimal? {
        val amount = runCatching { runtime.settings.normalize(currencyKey, BigDecimal(raw)) }.getOrNull()
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

    private fun parseCurrency(
        runtime: PluginRuntime,
        sender: CommandSender,
        raw: String?,
        defaultCurrency: String?
    ): String? {
        if (raw.isNullOrBlank()) {
            return defaultCurrency
        }
        val currency = runtime.settings.findCurrency(raw)
        if (currency == null) {
            sender.sendMessage("&cUnknown currency: &f$raw".colorize())
            return null
        }
        return currency.key
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
                plugin.replyLater(
                    sender,
                    "&cInsufficient funds. Balance: &f${runtime.settings.format(cause.currencyKey, cause.currentBalance)}"
                )
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
            "&e/balance [player] [currency] &7- view balances",
            "&e/pay <player> <amount> [currency] &7- transfer money",
            "&e/baltop [currency] [page] &7- view richest accounts",
            "&e/ecolink ledger [player] [currency] [page] &7- view transaction history",
            "&e/ecolink currencies &7- list configured currencies"
        )
        if (sender.hasPermission("ecolink.admin")) {
            lines += "&e/ecolink set <player> <amount> [currency] &7- set balance"
            lines += "&e/ecolink add <player> <amount> [currency] &7- add balance"
            lines += "&e/ecolink take <player> <amount> [currency] &7- take balance"
        }
        if (sender.hasPermission("ecolink.recharge")) {
            lines += "&e/ecolink recharge <player> <currency> <amount> <transactionId> [reason...] &7- idempotent recharge"
        }
        if (sender.hasPermission("ecolink.migrate")) {
            lines += "&e/ecolink migrate <cmi|essentials|all> [overwrite] &7- import old data"
        }
        return lines
    }

    private fun completePay(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> onlinePlayers(args[0])
            3 -> currencyKeys(args[2])
            else -> mutableListOf()
        }
    }

    private fun completeBalance(sender: CommandSender, args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> {
                val options = mutableListOf<String>()
                options += currencyKeys(args[0])
                if (sender.hasPermission("ecolink.balance.others")) {
                    options += onlinePlayers(args[0])
                }
                options.distinct().toMutableList()
            }

            2 -> currencyKeys(args[1])
            else -> mutableListOf()
        }
    }

    private fun completeBaltop(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> currencyKeys(args[0])
            else -> mutableListOf()
        }
    }

    private fun completeRoot(sender: CommandSender, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("help", "ledger", "currencies")
            if (sender.hasPermission("ecolink.admin")) {
                options += listOf("set", "add", "take")
            }
            if (sender.hasPermission("ecolink.recharge")) {
                options += "recharge"
            }
            if (sender.hasPermission("ecolink.migrate")) {
                options += "migrate"
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }

        return when (args[0].lowercase()) {
            "migrate" -> completeMigrate(args)
            "set", "add", "take" -> completeAdminMutation(args)
            "recharge" -> completeRecharge(args)
            "ledger" -> completeLedger(args)
            else -> mutableListOf()
        }
    }

    private fun completeMigrate(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            2 -> listOf("cmi", "essentials", "all").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
            3 -> listOf("overwrite").filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
            else -> mutableListOf()
        }
    }

    private fun completeAdminMutation(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            2 -> onlinePlayers(args[1])
            4 -> currencyKeys(args[3])
            else -> mutableListOf()
        }
    }

    private fun completeRecharge(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            2 -> onlinePlayers(args[1])
            3 -> currencyKeys(args[2])
            else -> mutableListOf()
        }
    }

    private fun completeLedger(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            2 -> {
                val options = mutableListOf<String>()
                options += onlinePlayers(args[1])
                options += currencyKeys(args[1])
                options.distinct().toMutableList()
            }

            3 -> currencyKeys(args[2])
            else -> mutableListOf()
        }
    }

    private fun currencyKeys(prefix: String): MutableList<String> {
        return plugin.bootstrapSettings.listCurrencies()
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun onlinePlayers(prefix: String): MutableList<String> {
        return Bukkit.getOnlinePlayers().map { it.name }.filter {
            it.startsWith(prefix, ignoreCase = true)
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
