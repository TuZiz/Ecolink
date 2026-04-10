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
import ym.ecolink.economy.ReasonTags
import ym.ecolink.i18n.ph
import ym.ecolink.migration.MigrationKind
import ym.ecolink.shop.ShopBusyException
import ym.ecolink.shop.ShopDisabledException
import ym.ecolink.shop.ShopDispatchException
import ym.ecolink.shop.ShopProductCurrencyDisabledException
import ym.ecolink.shop.UnknownShopProductException
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
            "money" -> handleMoney(sender, args)
            "balance" -> handleBalance(sender, args)
            "pay" -> handleStandalonePay(sender, args)
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
            "money" -> completeMoney(args, sender)
            "pay" -> completeStandalonePay(args)
            "balance" -> completeBalance(sender, args)
            "baltop" -> completeBaltop(args)
            else -> completeRoot(sender, args)
        }
    }

    private fun handleRoot(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        val keyword = args[0].lowercase()
        if (keyword == "reload") {
            return handleReload(sender)
        }

        val runtime = requireReady(sender) ?: return true
        return when (keyword) {
            "me" -> handleMe(sender, runtime)
            "buy" -> handleBuy(sender, runtime, args)
            "top" -> handleRootTop(sender, runtime, args)
            "pay" -> handleRootPay(sender, runtime, args)
            "create" -> handleCreateCurrency(sender, runtime, args)
            "give" -> handleGive(sender, runtime, args)
            "reset" -> handleReset(sender, runtime, args)
            "delete" -> handleDeleteCurrency(sender, runtime, args)
            "set" -> handleAdminMutation(sender, runtime, args, AdminMutation.SET)
            "add" -> handleAdminMutation(sender, runtime, args, AdminMutation.ADD)
            "take" -> handleAdminMutation(sender, runtime, args, AdminMutation.TAKE)
            "recharge" -> handleRecharge(sender, runtime, args)
            "migrate" -> handleMigration(sender, runtime, args)
            "ledger" -> handleLedger(sender, runtime, args)
            "currencies" -> handleCurrencies(sender, runtime)
            "help" -> {
                sendHelp(sender)
                true
            }

            else -> sendUsage(sender, "root")
        }
    }

    private fun handleMe(sender: CommandSender, runtime: PluginRuntime): Boolean {
        val player = sender as? Player ?: return sendPlayerOnly(sender)
        val identity = AccountIdentity(player.uniqueId, player.name)
        val visibleCurrencies = runtime.settings.listCurrencies().filter { it.playerVisible }
        if (visibleCurrencies.isEmpty()) {
            plugin.reply(sender, "messages.command.me.empty")
            return true
        }
        val futures = visibleCurrencies.associate { currency ->
            currency.key to runtime.economyService.getBalance(identity, currency.key)
        }
        CompletableFuture.allOf(*futures.values.toTypedArray()).whenComplete { _, error ->
            complete(sender, runtime, error) {
                plugin.replyLater(sender, "messages.command.me.header", ph("player", player.name))
                visibleCurrencies.forEach { currency ->
                    val record = futures.getValue(currency.key).join()
                    plugin.replyLater(
                        sender,
                        "messages.command.me.line",
                        ph("currency", currency.displayName),
                        ph("amount", runtime.settings.format(currency.key, record.balance))
                    )
                }
            }
        }
        return true
    }

    private fun handleBuy(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return sendPlayerOnly(sender)
        if (args.size < 2) {
            return sendUsage(sender, "buy")
        }
        runtime.shopService.purchase(player, args[1]).whenComplete { receipt, error ->
            complete(sender, runtime, error) {
                val currency = runtime.settings.requireCatalogCurrency(receipt.currencyKey)
                plugin.replyLater(
                    sender,
                    "messages.command.buy.success",
                    ph("product", receipt.product.displayName),
                    ph("currency", currency.displayName),
                    ph("amount", runtime.settings.format(receipt.currencyKey, receipt.amount)),
                    ph("balance", runtime.settings.format(receipt.currencyKey, receipt.remainingBalance))
                )
            }
        }
        return true
    }

    private fun handleRootTop(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (args.size < 2) {
            return sendUsage(sender, "top")
        }
        val currencyKey = parseCurrency(runtime, sender, args[1], null) ?: return true
        if (!runtime.settings.requireCurrency(currencyKey).leaderboardEnabled) {
            plugin.reply(sender, "messages.command.baltop.disabled-currency", ph("currency", runtime.settings.requireCurrency(currencyKey).displayName))
            return true
        }
        val page = parsePage(sender, args.getOrNull(2)) ?: return true
        return showTop(sender, runtime, currencyKey, page)
    }

    private fun handleRootPay(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return sendPlayerOnly(sender)
        if (!player.hasPermission("ecolink.pay")) {
            plugin.reply(sender, "messages.command.pay.no-permission")
            return true
        }
        if (args.size < 4) {
            return sendUsage(sender, "pay-root")
        }
        val currencyKey = parseCurrency(runtime, sender, args[2], null) ?: return true
        val amount = parsePositiveAmount(runtime, sender, args[3], currencyKey) ?: return true
        return executePay(sender, runtime, player, args[1], currencyKey, amount)
    }

    private fun handleCreateCurrency(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        if (!runtime.settings.currencyManagement.commandEnabled || !runtime.settings.currencyManagement.allowCreate) {
            plugin.reply(sender, "messages.command.currency.disabled")
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "create")
        }
        val currency = runtime.settings.findCatalogCurrency(args[1]) ?: run {
            plugin.reply(sender, "messages.command.currency.not-configured", ph("currency", args[1]))
            return true
        }
        if (runtime.settings.isCurrencyEnabled(currency.key)) {
            plugin.reply(sender, "messages.command.currency.already-enabled", ph("currency", currency.displayName))
            return true
        }
        plugin.updateEnabledCurrencies(runtime.settings.activeCurrencyKeys + currency.key).whenComplete { _, error ->
            if (error != null) {
                plugin.replyLater(
                    sender,
                    "messages.common.operation-failed",
                    ph("error", unwrap(error).message ?: unwrap(error).javaClass.simpleName)
                )
                return@whenComplete
            }
            plugin.replyLater(sender, "messages.command.currency.created", ph("currency", currency.displayName))
        }
        return true
    }

    private fun handleGive(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        if (args.size < 4) {
            return sendUsage(sender, "give")
        }
        val currencyKey = parseCurrency(runtime, sender, args[2], null) ?: return true
        val amount = parsePositiveAmount(runtime, sender, args[3], currencyKey) ?: return true
        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[1]))
                    return@complete
                }
                runtime.economyService.addBalance(target, currencyKey, amount, sender.name, ReasonTags.ADMIN_GIVE)
                    .whenComplete { record, mutationError ->
                        complete(sender, runtime, mutationError) {
                            plugin.replyLater(
                                sender,
                                "messages.command.admin.give",
                                ph("player", record.username),
                                ph("currency", runtime.settings.requireCurrency(currencyKey).displayName),
                                ph("amount", runtime.settings.format(currencyKey, record.balance))
                            )
                        }
                    }
            }
        }
        return true
    }

    private fun handleReset(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        if (args.size < 3) {
            return sendUsage(sender, "reset")
        }
        val currency = runtime.settings.findCurrency(args[2]) ?: run {
            plugin.reply(sender, "messages.validation.unknown-currency", ph("currency", args[2]))
            return true
        }
        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[1]))
                    return@complete
                }
                runtime.economyService.setBalance(
                    target = target,
                    currencyKey = currency.key,
                    amount = currency.startingBalance,
                    actor = sender.name,
                    reason = ReasonTags.ADMIN_RESET
                ).whenComplete { record, resetError ->
                    complete(sender, runtime, resetError) {
                        plugin.replyLater(
                            sender,
                            "messages.command.admin.reset",
                            ph("player", record.username),
                            ph("currency", currency.displayName),
                            ph("amount", runtime.settings.format(currency.key, record.balance))
                        )
                    }
                }
            }
        }
        return true
    }

    private fun handleDeleteCurrency(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        if (!runtime.settings.currencyManagement.commandEnabled || !runtime.settings.currencyManagement.allowDelete) {
            plugin.reply(sender, "messages.command.currency.disabled")
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "delete")
        }
        val currency = runtime.settings.findCurrency(args[1]) ?: run {
            plugin.reply(sender, "messages.validation.unknown-currency", ph("currency", args[1]))
            return true
        }
        if (runtime.settings.currencyManagement.isProtected(currency.key, runtime.settings.defaultCurrencyKey)) {
            plugin.reply(sender, "messages.command.currency.protected", ph("currency", currency.displayName))
            return true
        }
        runtime.economyService.deleteCurrencyData(currency.key)
            .thenCompose { summary ->
                plugin.updateEnabledCurrencies(runtime.settings.activeCurrencyKeys - currency.key)
                    .thenApply { summary }
            }.whenComplete { summary, error ->
                if (error != null) {
                    plugin.replyLater(
                        sender,
                        "messages.common.operation-failed",
                        ph("error", unwrap(error).message ?: unwrap(error).javaClass.simpleName)
                    )
                    return@whenComplete
                }
                plugin.replyLater(
                    sender,
                    "messages.command.currency.deleted",
                    ph("currency", currency.displayName),
                    ph("balances", summary.balancesDeleted),
                    ph("ledger", summary.ledgerDeleted),
                    ph("recharges", summary.rechargesDeleted)
                )
            }
        return true
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        plugin.reply(sender, "messages.command.reload.started")
        plugin.reloadRuntime().whenComplete { _, error ->
            if (error != null) {
                plugin.replyLater(
                    sender,
                    "messages.common.operation-failed",
                    ph("error", unwrap(error).message ?: unwrap(error).javaClass.simpleName)
                )
                return@whenComplete
            }
            plugin.replyLater(sender, "messages.command.reload.success")
        }
        return true
    }

    private fun handleMoney(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val vaultCurrency = runtime.settings.vaultCurrency()
        if (args.isEmpty()) {
            val player = sender as? Player ?: return sendUsage(sender, "money-root")
            return queryBalance(
                sender,
                runtime,
                AccountIdentity(player.uniqueId, player.name),
                vaultCurrency.key,
                selfView = true
            )
        }

        return when (args[0].lowercase()) {
            "give" -> {
                if (!sender.hasPermission("ecolink.admin")) {
                    plugin.reply(sender, "messages.command.admin.no-permission")
                    return true
                }
                if (args.size < 3) {
                    return sendUsage(sender, "money-give")
                }
                val amount = parsePositiveAmount(runtime, sender, args[2], vaultCurrency.key) ?: return true
                handleMoneyAdminMutation(sender, runtime, args[1], amount, MoneyAdminMutation.GIVE)
            }

            "set" -> {
                if (!sender.hasPermission("ecolink.admin")) {
                    plugin.reply(sender, "messages.command.admin.no-permission")
                    return true
                }
                if (args.size < 3) {
                    return sendUsage(sender, "money-set")
                }
                val amount = parseNonNegativeAmount(runtime, sender, args[2], vaultCurrency.key) ?: return true
                handleMoneyAdminMutation(sender, runtime, args[1], amount, MoneyAdminMutation.SET)
            }

            "take" -> {
                if (!sender.hasPermission("ecolink.admin")) {
                    plugin.reply(sender, "messages.command.admin.no-permission")
                    return true
                }
                if (args.size < 3) {
                    return sendUsage(sender, "money-take")
                }
                val amount = parsePositiveAmount(runtime, sender, args[2], vaultCurrency.key) ?: return true
                handleMoneyAdminMutation(sender, runtime, args[1], amount, MoneyAdminMutation.TAKE)
            }

            "pay" -> {
                val player = sender as? Player ?: return sendPlayerOnly(sender)
                if (!player.hasPermission("ecolink.pay")) {
                    plugin.reply(sender, "messages.command.pay.no-permission")
                    return true
                }
                if (args.size < 3) {
                    return sendUsage(sender, "money-root")
                }
                val amount = parsePositiveAmount(runtime, sender, args[2], vaultCurrency.key) ?: return true
                executePay(sender, runtime, player, args[1], vaultCurrency.key, amount)
            }

            "top" -> {
                if (!vaultCurrency.leaderboardEnabled) {
                    plugin.reply(sender, "messages.command.baltop.disabled-currency", ph("currency", vaultCurrency.displayName))
                    return true
                }
                val page = parsePage(sender, args.getOrNull(1)) ?: return true
                showTop(sender, runtime, vaultCurrency.key, page)
            }

            "help" -> sendUsage(sender, "money-root")
            else -> {
                if (!sender.hasPermission("ecolink.balance.others")) {
                    plugin.reply(sender, "messages.command.balance.no-permission-others")
                    return true
                }
                resolveTarget(runtime, args[0]).whenComplete { target, error ->
                    complete(sender, runtime, error) {
                        if (target == null) {
                            plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[0]))
                            return@complete
                        }
                        queryBalance(sender, runtime, target, vaultCurrency.key, selfView = false)
                    }
                }
                true
            }
        }
    }

    private fun handleMoneyAdminMutation(
        sender: CommandSender,
        runtime: PluginRuntime,
        targetSelector: String,
        amount: BigDecimal,
        mutation: MoneyAdminMutation
    ): Boolean {
        val currency = runtime.settings.vaultCurrency()
        resolveTarget(runtime, targetSelector).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", targetSelector))
                    return@complete
                }
                val future = when (mutation) {
                    MoneyAdminMutation.GIVE -> runtime.economyService.addBalance(target, currency.key, amount, sender.name, ReasonTags.ADMIN_GIVE)
                    MoneyAdminMutation.SET -> runtime.economyService.setBalance(target, currency.key, amount, sender.name, ReasonTags.ADMIN_SET)
                    MoneyAdminMutation.TAKE -> runtime.economyService.takeBalance(target, currency.key, amount, sender.name, ReasonTags.ADMIN_TAKE)
                }
                future.whenComplete { record, mutationError ->
                    complete(sender, runtime, mutationError) {
                        plugin.replyLater(
                            sender,
                            mutation.messageKey,
                            ph("player", record.username),
                            ph("currency", currency.displayName),
                            ph("amount", runtime.settings.format(currency.key, record.balance))
                        )
                    }
                }
            }
        }
        return true
    }

    private fun handleBalance(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val vaultCurrency = runtime.settings.vaultCurrency()
        if (args.isEmpty()) {
            val player = sender as? Player ?: return sendUsage(sender, "balance-other")
            return queryBalance(sender, runtime, AccountIdentity(player.uniqueId, player.name), vaultCurrency.key, selfView = true)
        }
        if (args.size > 1) {
            return sendUsage(sender, "balance-other")
        }
        if (!sender.hasPermission("ecolink.balance.others")) {
            plugin.reply(sender, "messages.command.balance.no-permission-others")
            return true
        }
        resolveTarget(runtime, args[0]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[0]))
                    return@complete
                }
                queryBalance(sender, runtime, target, vaultCurrency.key, selfView = false)
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
                val messageKey = if (selfView) {
                    "messages.command.balance.self"
                } else {
                    "messages.command.balance.other"
                }
                plugin.replyLater(
                    sender,
                    messageKey,
                    ph("player", record.username),
                    ph("currency", currency.displayName),
                    ph("amount", runtime.settings.format(currency.key, record.balance))
                )
            }
        }
        return true
    }

    private fun handleStandalonePay(sender: CommandSender, args: Array<out String>): Boolean {
        val runtime = requireReady(sender) ?: return true
        val player = sender as? Player ?: return sendPlayerOnly(sender)
        if (!player.hasPermission("ecolink.pay")) {
            plugin.reply(sender, "messages.command.pay.no-permission")
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "pay")
        }
        if (args.size > 2) {
            return sendUsage(sender, "pay")
        }
        val vaultCurrency = runtime.settings.vaultCurrency()
        val amount = parsePositiveAmount(runtime, sender, args[1], vaultCurrency.key) ?: return true
        return executePay(sender, runtime, player, args[0], vaultCurrency.key, amount)
    }

    private fun executePay(
        sender: CommandSender,
        runtime: PluginRuntime,
        player: Player,
        targetSelector: String,
        currencyKey: String,
        amount: BigDecimal
    ): Boolean {
        val currency = runtime.settings.requireCurrency(currencyKey)
        if (!currency.transferable) {
            plugin.reply(sender, "messages.command.pay.currency-locked", ph("currency", currency.displayName))
            return true
        }
        val source = AccountIdentity(player.uniqueId, player.name)

        resolveTarget(runtime, targetSelector).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", targetSelector))
                    return@complete
                }
                if (target.uuid == source.uuid) {
                    plugin.replyLater(sender, "messages.command.pay.self")
                    return@complete
                }
                runtime.economyService.transfer(
                    source = source,
                    target = target,
                    currencyKey = currency.key,
                    amount = amount,
                    actor = player.name,
                    reason = ReasonTags.PLAYER_PAY
                ).whenComplete { receipt, payError ->
                    complete(sender, runtime, payError) {
                        plugin.replyLater(
                            sender,
                            "messages.command.pay.sent",
                            ph("target", receipt.to.username),
                            ph("currency", currency.displayName),
                            ph("amount", runtime.settings.format(currency.key, amount)),
                            ph("balance", runtime.settings.format(currency.key, receipt.from.balance))
                        )
                        Bukkit.getPlayer(receipt.to.uuid)?.takeIf { it.isOnline }?.let { targetPlayer ->
                            plugin.replyLater(
                                targetPlayer,
                                "messages.command.pay.received",
                                ph("currency", currency.displayName),
                                ph("amount", runtime.settings.format(currency.key, amount)),
                                ph("player", player.name)
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
        val vaultCurrency = runtime.settings.vaultCurrency()
        if (!vaultCurrency.leaderboardEnabled) {
            plugin.reply(sender, "messages.command.baltop.disabled-currency", ph("currency", vaultCurrency.displayName))
            return true
        }
        val page = parsePage(sender, args.getOrNull(0)) ?: return true
        return showTop(sender, runtime, vaultCurrency.key, page)
    }

    private fun showTop(sender: CommandSender, runtime: PluginRuntime, currencyKey: String, page: Int): Boolean {
        runtime.economyService.getTopBalances(page, currencyKey).whenComplete { records, error ->
            complete(sender, runtime, error) {
                val currency = runtime.settings.requireCurrency(currencyKey)
                val startRank = (page - 1) * runtime.settings.feature.topPageSize + 1
                plugin.replyLater(
                    sender,
                    "messages.command.baltop.header",
                    ph("currency", currency.displayName),
                    ph("page", page)
                )
                if (records.isEmpty()) {
                    plugin.replyLater(sender, "messages.command.baltop.empty")
                    return@complete
                }
                records.forEachIndexed { index, record ->
                    plugin.replyLater(
                        sender,
                        "messages.command.baltop.line",
                        ph("rank", startRank + index),
                        ph("player", record.username),
                        ph("amount", runtime.settings.format(currency.key, record.balance))
                    )
                }
            }
        }
        return true
    }

    private fun handleAdminMutation(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>,
        mutation: AdminMutation
    ): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        if (args.size < 3) {
            return sendUsage(sender, mutation.usageKey)
        }

        val currencyKey = parseCurrency(runtime, sender, args.getOrNull(3), runtime.settings.defaultCurrencyKey) ?: return true
        val amount = when (mutation) {
            AdminMutation.SET -> parseNonNegativeAmount(runtime, sender, args[2], currencyKey)
            else -> parsePositiveAmount(runtime, sender, args[2], currencyKey)
        } ?: return true

        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[1]))
                    return@complete
                }
                val future = when (mutation) {
                    AdminMutation.SET -> runtime.economyService.setBalance(target, currencyKey, amount, sender.name, ReasonTags.ADMIN_SET)
                    AdminMutation.ADD -> runtime.economyService.addBalance(target, currencyKey, amount, sender.name, ReasonTags.ADMIN_ADD)
                    AdminMutation.TAKE -> runtime.economyService.takeBalance(target, currencyKey, amount, sender.name, ReasonTags.ADMIN_TAKE)
                }
                future.whenComplete { record, mutationError ->
                    complete(sender, runtime, mutationError) {
                        plugin.replyLater(
                            sender,
                            mutation.messageKey,
                            ph("player", record.username),
                            ph("currency", runtime.settings.requireCurrency(currencyKey).displayName),
                            ph("amount", runtime.settings.format(currencyKey, record.balance))
                        )
                    }
                }
            }
        }
        return true
    }

    private fun handleRecharge(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.recharge")) {
            plugin.reply(sender, "messages.command.recharge.no-permission")
            return true
        }
        if (args.size < 5) {
            return sendUsage(sender, "recharge")
        }

        val currencyKey = parseCurrency(runtime, sender, args[2], null) ?: return true
        val amount = parsePositiveAmount(runtime, sender, args[3], currencyKey) ?: return true
        val transactionId = args[4].trim()
        if (transactionId.isBlank()) {
            plugin.reply(sender, "messages.command.recharge.blank-transaction-id")
            return true
        }
        val reason = if (args.size > 5) {
            ReasonTags.custom(args.copyOfRange(5, args.size).joinToString(" "), ReasonTags.RECHARGE_MANUAL)
        } else {
            ReasonTags.RECHARGE_MANUAL
        }

        resolveTarget(runtime, args[1]).whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found-selector", ph("selector", args[1]))
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
                        sendRechargeResult(sender, runtime, receipt)
                    }
                }
            }
        }
        return true
    }

    private fun sendRechargeResult(sender: CommandSender, runtime: PluginRuntime, receipt: RechargeReceipt) {
        val path = if (receipt.duplicate) {
            "messages.command.recharge.duplicate"
        } else {
            "messages.command.recharge.applied"
        }
        plugin.replyLater(
            sender,
            path,
            ph("transaction", receipt.transactionId),
            ph("player", receipt.record.username),
            ph("currency", runtime.settings.requireCatalogCurrency(receipt.record.currencyKey).displayName),
            ph("amount", runtime.settings.format(receipt.record.currencyKey, receipt.amount)),
            ph("balance", runtime.settings.format(receipt.record.currencyKey, receipt.record.balance))
        )
    }

    private fun handleMigration(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("ecolink.migrate")) {
            plugin.reply(sender, "messages.command.migrate.no-permission")
            return true
        }
        if (args.size < 2) {
            return sendUsage(sender, "migrate")
        }
        val source = args[1].lowercase()
        if (source == "storage" || source == "db" || source == "database" || source == "remote") {
            return handleStorageMigration(sender, runtime, args)
        }
        val kind = MigrationKind.from(args[1]) ?: run {
            plugin.reply(sender, "messages.command.migrate.unknown-source", ph("source", args[1]))
            return true
        }
        val overwrite = parseOverwrite(args.getOrNull(2))
        plugin.reply(sender, "messages.command.migrate.started")
        runtime.migrationService.migrate(kind, overwrite).whenComplete { summary, error ->
            complete(sender, runtime, error) {
                plugin.replyLater(
                    sender,
                    "messages.command.migrate.summary",
                    ph("source", summary.sourceLabel()),
                    ph("discovered", summary.discovered),
                    ph("inserted", summary.inserted),
                    ph("updated", summary.updated),
                    ph("skipped", summary.skipped),
                    ph("failed", summary.failed)
                )
                summary.reports.forEach { report ->
                    plugin.replyLater(
                        sender,
                        "messages.command.migrate.report",
                        ph("source", report.source),
                        ph("discovered", report.discovered),
                        ph("inserted", report.inserted),
                        ph("updated", report.updated),
                        ph("skipped", report.skipped),
                        ph("failed", report.failed)
                    )
                    report.sampleErrors.forEach { sample ->
                        plugin.replyLater(sender, "messages.command.migrate.sample-error", ph("error", sample))
                    }
                }
            }
        }
        return true
    }

    private fun handleStorageMigration(
        sender: CommandSender,
        runtime: PluginRuntime,
        args: Array<out String>
    ): Boolean {
        val target = runtime.settings.migration.storageTarget
        if (!target.enabled) {
            plugin.reply(sender, "messages.command.migrate.storage-target-disabled")
            return true
        }
        if (target.storage.type.id == "sqlite") {
            plugin.reply(sender, "messages.command.migrate.storage-target-invalid")
            return true
        }
        if (runtime.settings.storage.sameEndpointAs(target.storage)) {
            plugin.reply(sender, "messages.command.migrate.storage-target-same")
            return true
        }
        val overwrite = parseOverwrite(args.getOrNull(2))
        plugin.reply(sender, "messages.command.migrate.storage-started")
        runtime.migrationService.migrateStorage(overwrite).whenComplete { summary, error ->
            complete(sender, runtime, error) {
                plugin.replyLater(
                    sender,
                    "messages.command.migrate.storage-summary",
                    ph("source", summary.source),
                    ph("target", summary.target),
                    ph("discovered", summary.discovered),
                    ph("inserted", summary.inserted),
                    ph("updated", summary.updated),
                    ph("skipped", summary.skipped),
                    ph("failed", summary.failed)
                )
                summary.reports.forEach { report ->
                    plugin.replyLater(
                        sender,
                        "messages.command.migrate.storage-report",
                        ph("table", report.table),
                        ph("discovered", report.discovered),
                        ph("inserted", report.inserted),
                        ph("updated", report.updated),
                        ph("skipped", report.skipped),
                        ph("failed", report.failed)
                    )
                    report.sampleErrors.forEach { sample ->
                        plugin.replyLater(
                            sender,
                            "messages.command.migrate.sample-error",
                            ph("error", "${report.table}: $sample")
                        )
                    }
                }
            }
        }
        return true
    }

    private fun handleLedger(sender: CommandSender, runtime: PluginRuntime, args: Array<out String>): Boolean {
        if (!sender.hasPermission("ecolink.ledger")) {
            plugin.reply(sender, "messages.command.ledger.no-permission")
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
            val player = selfPlayer ?: return sendUsage(sender, "ledger")
            CompletableFuture.completedFuture(AccountIdentity(player.uniqueId, player.name))
        } else {
            if (!sender.hasPermission("ecolink.ledger.others")) {
                plugin.reply(sender, "messages.command.ledger.no-permission-others")
                return true
            }
            resolveTarget(runtime, selector)
        }

        targetFuture.whenComplete { target, error ->
            complete(sender, runtime, error) {
                if (target == null) {
                    plugin.replyLater(sender, "messages.common.account-not-found")
                    return@complete
                }
                runtime.economyService.getLedger(target, page, currencyKey).whenComplete { entries, ledgerError ->
                    complete(sender, runtime, ledgerError) {
                        val currency = runtime.settings.requireCurrency(currencyKey)
                        plugin.replyLater(
                            sender,
                            "messages.command.ledger.header",
                            ph("player", target.username),
                            ph("currency", currency.displayName),
                            ph("page", page)
                        )
                        if (entries.isEmpty()) {
                            plugin.replyLater(sender, "messages.command.ledger.empty")
                            return@complete
                        }
                        entries.forEach { entry ->
                            sendLedgerLine(sender, runtime, entry)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handleCurrencies(sender: CommandSender, runtime: PluginRuntime): Boolean {
        if (!sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.command.admin.no-permission")
            return true
        }
        plugin.reply(sender, "messages.command.currencies.header")
        runtime.settings.listCurrencies().forEach { currency ->
            val tags = buildList {
                if (currency.key == runtime.settings.defaultCurrencyKey) add(plugin.messages.raw("messages.command.currencies.tags.default"))
                if (currency.vaultPrimary) add(plugin.messages.raw("messages.command.currencies.tags.vault"))
                if (!currency.transferable) add(plugin.messages.raw("messages.command.currencies.tags.no-pay"))
                if (!currency.playerVisible) add(plugin.messages.raw("messages.command.currencies.tags.hidden"))
                if (!currency.leaderboardEnabled) add(plugin.messages.raw("messages.command.currencies.tags.no-top"))
            }
            val tagBlock = if (tags.isEmpty()) "" else "[${tags.joinToString(", ")}]"
            plugin.reply(
                sender,
                "messages.command.currencies.line",
                ph("key", currency.key),
                ph("display", currency.displayName),
                ph("amount", runtime.settings.format(currency.key, currency.startingBalance)),
                ph("tags", tagBlock)
            )
        }
        return true
    }

    private fun sendLedgerLine(sender: CommandSender, runtime: PluginRuntime, entry: LedgerEntry) {
        val time = timeFormatter.format(entry.createdAt)
        val reason = entry.reason?.takeIf { it.isNotBlank() } ?: plugin.messages.raw("messages.labels.not-available")
        plugin.replyLater(
            sender,
            "messages.command.ledger.line",
            ph("time", time),
            ph("action", entry.action.name),
            ph("amount", runtime.settings.format(entry.currencyKey, entry.amount)),
            ph("balance", runtime.settings.format(entry.currencyKey, entry.balanceAfter)),
            ph("server", entry.sourceServer),
            ph("reason", reason),
            ph("transaction", entry.idempotencyKey ?: plugin.messages.raw("messages.labels.not-available"))
        )
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
            plugin.reply(sender, "messages.system.startup-failed", ph("error", it.message ?: it.javaClass.simpleName))
            return null
        }
        plugin.reply(sender, "messages.system.starting")
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
            plugin.reply(sender, "messages.validation.invalid-amount", ph("input", raw))
            return null
        }
        val valid = if (allowZero) amount.signum() >= 0 else amount.signum() > 0
        if (!valid) {
            plugin.reply(
                sender,
                if (allowZero) "messages.validation.amount-non-negative" else "messages.validation.amount-positive"
            )
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
            plugin.reply(sender, "messages.validation.unknown-currency", ph("currency", raw))
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
            plugin.reply(sender, "messages.validation.invalid-page", ph("input", raw))
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

    private fun sendUsage(sender: CommandSender, usageKey: String): Boolean {
        plugin.reply(sender, "messages.common.usage", ph("usage", plugin.messages.raw("usage.$usageKey")))
        return true
    }

    private fun sendPlayerOnly(sender: CommandSender): Boolean {
        plugin.reply(sender, "messages.common.player-only")
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
                    "messages.common.insufficient-funds",
                    ph("amount", runtime.settings.format(cause.currencyKey, cause.currentBalance))
                )
            }

            is ShopDisabledException -> plugin.replyLater(sender, "messages.command.buy.disabled")
            is UnknownShopProductException -> plugin.replyLater(sender, "messages.command.buy.unknown-product", ph("product", cause.productKey))
            is ShopProductCurrencyDisabledException -> plugin.replyLater(sender, "messages.command.buy.currency-disabled", ph("currency", cause.currencyKey))
            is ShopBusyException -> plugin.replyLater(sender, "messages.command.buy.in-progress")
            is ShopDispatchException -> {
                plugin.logger.log(Level.SEVERE, "Ecolink shop dispatch failed.", cause)
                plugin.replyLater(sender, "messages.command.buy.delivery-failed")
            }

            else -> {
                plugin.logger.log(Level.SEVERE, "Ecolink command failed.", cause)
                plugin.replyLater(
                    sender,
                    "messages.common.operation-failed",
                    ph("error", cause.message ?: cause.javaClass.simpleName)
                )
            }
        }
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.reply(sender, "messages.help.header")
        plugin.reply(sender, "messages.help.me")
        plugin.reply(sender, "messages.help.buy")
        plugin.reply(sender, "messages.help.top")
        plugin.reply(sender, "messages.help.pay")
        plugin.reply(sender, "messages.help.money")
        if (sender.hasPermission("ecolink.admin")) {
            plugin.reply(sender, "messages.help.create")
            plugin.reply(sender, "messages.help.give")
            plugin.reply(sender, "messages.help.reset")
            plugin.reply(sender, "messages.help.delete")
            plugin.reply(sender, "messages.help.reload")
        }
        if (sender.hasPermission("ecolink.migrate")) {
            plugin.reply(sender, "messages.help.migrate-storage")
        }
    }

    private fun completeStandalonePay(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> onlinePlayers(args[0])
            2 -> listOf("10", "100", "1000").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
            else -> mutableListOf()
        }
    }

    private fun completeMoney(args: Array<out String>, sender: CommandSender): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("pay", "top", "help")
            if (sender.hasPermission("ecolink.admin")) {
                options += listOf("give", "set", "take")
            }
            if (sender.hasPermission("ecolink.balance.others")) {
                options += onlinePlayers(args[0])
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }.distinct().toMutableList()
        }
        return when (args[0].lowercase()) {
            "pay" -> when (args.size) {
                2 -> onlinePlayers(args[1])
                3 -> listOf("10", "100", "1000").filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
                else -> mutableListOf()
            }

            "give", "set", "take" -> when (args.size) {
                2 -> if (sender.hasPermission("ecolink.admin")) onlinePlayers(args[1]) else mutableListOf()
                3 -> if (sender.hasPermission("ecolink.admin")) {
                    listOf("10", "100", "1000").filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
                } else {
                    mutableListOf()
                }
                else -> mutableListOf()
            }

            "top" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun completeBalance(sender: CommandSender, args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> if (sender.hasPermission("ecolink.balance.others")) onlinePlayers(args[0]) else mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun completeBaltop(args: Array<out String>): MutableList<String> {
        return mutableListOf()
    }

    private fun completeRoot(sender: CommandSender, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("help", "me", "buy", "top", "pay")
            if (sender.hasPermission("ecolink.admin")) {
                options += listOf("create", "give", "reset", "delete", "reload", "currencies")
            }
            if (sender.hasPermission("ecolink.ledger")) {
                options += "ledger"
            }
            if (sender.hasPermission("ecolink.recharge")) {
                options += "recharge"
            }
            if (sender.hasPermission("ecolink.migrate")) {
                options += "migrate"
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }.distinct().toMutableList()
        }

        return when (args[0].lowercase()) {
            "buy" -> when (args.size) {
                2 -> productKeys(args[1])
                else -> mutableListOf()
            }

            "top" -> when (args.size) {
                2 -> leaderboardCurrencyKeys(args[1])
                else -> mutableListOf()
            }

            "pay" -> when (args.size) {
                2 -> onlinePlayers(args[1])
                3 -> transferableCurrencyKeys(args[2])
                else -> mutableListOf()
            }

            "create" -> when (args.size) {
                2 -> disabledCurrencyKeys(args[1])
                else -> mutableListOf()
            }

            "give" -> when (args.size) {
                2 -> onlinePlayers(args[1])
                3 -> currencyKeys(args[2])
                else -> mutableListOf()
            }

            "reset" -> when (args.size) {
                2 -> onlinePlayers(args[1])
                3 -> currencyKeys(args[2])
                else -> mutableListOf()
            }

            "delete" -> when (args.size) {
                2 -> removableCurrencyKeys(args[1])
                else -> mutableListOf()
            }

            "migrate" -> completeMigrate(args)
            "set", "add", "take" -> completeAdminMutation(args)
            "recharge" -> completeRecharge(args)
            "ledger" -> completeLedger(args)
            else -> mutableListOf()
        }
    }

    private fun completeMigrate(args: Array<out String>): MutableList<String> {
        return when (args.size) {
            2 -> listOf("cmi", "essentials", "all", "storage").filter {
                it.startsWith(args[1], ignoreCase = true)
            }.toMutableList()
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
        return plugin.activeSettings().listCurrencies()
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun transferableCurrencyKeys(prefix: String): MutableList<String> {
        return plugin.activeSettings().listCurrencies()
            .filter { it.transferable }
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun leaderboardCurrencyKeys(prefix: String): MutableList<String> {
        return plugin.activeSettings().listCurrencies()
            .filter { it.leaderboardEnabled }
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun disabledCurrencyKeys(prefix: String): MutableList<String> {
        return plugin.activeSettings().listDisabledCurrencies()
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun removableCurrencyKeys(prefix: String): MutableList<String> {
        val settings = plugin.activeSettings()
        return settings.listCurrencies()
            .filterNot { settings.currencyManagement.isProtected(it.key, settings.defaultCurrencyKey) }
            .map { it.key }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()
    }

    private fun productKeys(prefix: String): MutableList<String> {
        return plugin.activeSettings().shop.listProducts()
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

private enum class AdminMutation(
    val usageKey: String,
    val messageKey: String
) {
    SET("admin-set", "messages.command.admin.set"),
    ADD("admin-add", "messages.command.admin.add"),
    TAKE("admin-take", "messages.command.admin.take")
}

private enum class MoneyAdminMutation(
    val usageKey: String,
    val messageKey: String
) {
    GIVE("money-give", "messages.command.admin.give"),
    SET("money-set", "messages.command.admin.set"),
    TAKE("money-take", "messages.command.admin.take")
}
