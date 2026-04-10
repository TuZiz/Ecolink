package ym.ecolink.shop

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.ecolink.config.CurrencyDefinition
import ym.ecolink.config.PluginSettings
import ym.ecolink.config.ShopProductDefinition
import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.EconomyService
import ym.ecolink.platform.ServerTaskDispatcher
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ShopService(
    private val plugin: JavaPlugin,
    private val settings: PluginSettings,
    private val economyService: EconomyService,
    private val dispatcher: ServerTaskDispatcher
) {

    private val purchasingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun purchase(player: Player, productKey: String): CompletableFuture<ShopPurchaseReceipt> {
        if (!settings.shop.enabled) {
            return failedFuture(ShopDisabledException())
        }
        val product = settings.shop.findProduct(productKey)
            ?: return failedFuture(UnknownShopProductException(productKey))
        val currency = settings.findCurrency(product.currencyKey)
            ?: return failedFuture(ShopProductCurrencyDisabledException(product.currencyKey))
        if (!purchasingPlayers.add(player.uniqueId)) {
            return failedFuture(ShopBusyException())
        }

        val identity = AccountIdentity(player.uniqueId, player.name)
        return economyService.takeBalance(
            target = identity,
            currencyKey = currency.key,
            amount = product.price,
            actor = player.name,
            reason = "shop-buy:${product.key}"
        ).thenCompose { record ->
            val dispatchFuture = CompletableFuture<ShopPurchaseReceipt>()
            dispatcher.runGlobal {
                try {
                    deliverProduct(player, product, currency)
                    dispatchFuture.complete(
                        ShopPurchaseReceipt(
                            product = product,
                            currencyKey = currency.key,
                            amount = product.price,
                            remainingBalance = record.balance
                        )
                    )
                } catch (error: Throwable) {
                    dispatchFuture.completeExceptionally(error)
                }
            }
            dispatchFuture
        }.whenComplete { _, _ ->
            purchasingPlayers.remove(player.uniqueId)
        }
    }

    private fun deliverProduct(
        player: Player,
        product: ShopProductDefinition,
        currency: CurrencyDefinition
    ) {
        product.commands.forEach { command ->
            val rendered = command
                .replace("<player>", player.name, ignoreCase = false)
                .replace("<player_uuid>", player.uniqueId.toString(), ignoreCase = false)
                .replace("<product>", product.key, ignoreCase = false)
                .replace("<product_display>", product.displayName, ignoreCase = false)
                .replace("<currency>", currency.key, ignoreCase = false)
                .replace("<currency_display>", currency.displayName, ignoreCase = false)
                .replace("<price>", settings.format(currency.key, product.price), ignoreCase = false)
                .replace("<price_plain>", product.price.toPlainString(), ignoreCase = false)
                .trim()
                .removePrefix("/")
            val executed = plugin.server.dispatchCommand(plugin.server.consoleSender, rendered)
            if (!executed) {
                throw ShopDispatchException("Failed to dispatch shop command for product ${product.key}: $rendered")
            }
        }
    }

    private fun <T> failedFuture(error: Throwable): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        future.completeExceptionally(error)
        return future
    }
}
