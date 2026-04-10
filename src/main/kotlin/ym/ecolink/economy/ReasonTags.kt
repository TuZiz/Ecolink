package ym.ecolink.economy

object ReasonTags {
    const val ADMIN_GIVE = "admin.give"
    const val ADMIN_SET = "admin.set"
    const val ADMIN_ADD = "admin.add"
    const val ADMIN_TAKE = "admin.take"
    const val ADMIN_RESET = "admin.reset"
    const val PLAYER_PAY = "player.pay"
    const val SHOP_BUY_PREFIX = "shop.buy"
    const val RECHARGE_MANUAL = "recharge.manual"
    const val VAULT_DEPOSIT = "vault.deposit"
    const val VAULT_WITHDRAW = "vault.withdraw"
    const val MIGRATION_CMI = "migration.cmi"
    const val MIGRATION_ESSENTIALS = "migration.essentials"

    fun shopBuy(productKey: String): String = "$SHOP_BUY_PREFIX.${sanitize(productKey)}"

    fun custom(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) {
            return fallback
        }
        val normalized = raw.trim()
            .lowercase()
            .replace(Regex("\\s+"), ".")
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim('.')
        return normalized.ifBlank { fallback }
    }

    private fun sanitize(raw: String): String {
        return raw.lowercase().replace(Regex("[^a-z0-9._-]"), "")
    }
}
