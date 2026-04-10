package ym.ecolink.economy

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AccountIdentity(
    val uuid: UUID,
    val username: String
)

data class AccountRecord(
    val uuid: UUID,
    val username: String,
    val currencyKey: String,
    val balance: BigDecimal,
    val version: Long,
    val updatedAt: Instant
) {
    fun toIdentity(): AccountIdentity = AccountIdentity(uuid, username)
}

data class TransferReceipt(
    val from: AccountRecord,
    val to: AccountRecord
) {
    val currencyKey: String
        get() = from.currencyKey
}

data class RechargeReceipt(
    val transactionId: String,
    val record: AccountRecord,
    val amount: BigDecimal,
    val duplicate: Boolean,
    val actor: String?,
    val reason: String?,
    val sourceServer: String,
    val processedAt: Instant
)

data class LedgerEntry(
    val id: UUID,
    val accountUuid: UUID,
    val currencyKey: String,
    val counterpartyUuid: UUID?,
    val action: LedgerAction,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val actor: String?,
    val reason: String?,
    val sourceServer: String,
    val idempotencyKey: String?,
    val createdAt: Instant
)

data class ImportedBalance(
    val uuid: UUID,
    val username: String,
    val currencyKey: String,
    val balance: BigDecimal
) {
    fun toIdentity(): AccountIdentity = AccountIdentity(uuid, username)
}

enum class LedgerAction {
    SET,
    ADD,
    TAKE,
    TRANSFER_IN,
    TRANSFER_OUT,
    MIGRATION,
    RECHARGE
}

data class CurrencyDeletionSummary(
    val currencyKey: String,
    val balancesDeleted: Int,
    val ledgerDeleted: Int,
    val rechargesDeleted: Int
)

data class BalanceSyncRecord(
    val serverId: String,
    val uuid: UUID,
    val username: String,
    val currencyKey: String,
    val balance: BigDecimal,
    val version: Long,
    val updatedAt: Instant
)

class InsufficientFundsException(
    val currencyKey: String,
    val currentBalance: BigDecimal,
    val attempted: BigDecimal
) : IllegalStateException(
    "Insufficient funds. currency=$currencyKey current=$currentBalance attempted=$attempted"
)
