package ym.ecolink.shop

import ym.ecolink.config.ShopProductDefinition
import java.math.BigDecimal

data class ShopPurchaseReceipt(
    val product: ShopProductDefinition,
    val currencyKey: String,
    val amount: BigDecimal,
    val remainingBalance: BigDecimal
)

class ShopDisabledException : IllegalStateException("Shop is disabled.")

class UnknownShopProductException(
    val productKey: String
) : IllegalArgumentException("Unknown shop product: $productKey")

class ShopProductCurrencyDisabledException(
    val currencyKey: String
) : IllegalStateException("Shop product currency is disabled: $currencyKey")

class ShopBusyException : IllegalStateException("A purchase is already in progress for this player.")

class ShopDispatchException(message: String) : IllegalStateException(message)
