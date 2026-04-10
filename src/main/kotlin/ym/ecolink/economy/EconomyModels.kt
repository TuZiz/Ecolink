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
    val balance: BigDecimal,
    val version: Long,
    val updatedAt: Instant
) {
    fun toIdentity(): AccountIdentity = AccountIdentity(uuid, username)
}

data class TransferReceipt(
    val from: AccountRecord,
    val to: AccountRecord
)

data class LedgerEntry(
    val id: UUID,
    val accountUuid: UUID,
    val counterpartyUuid: UUID?,
    val action: LedgerAction,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val actor: String?,
    val reason: String?,
    val sourceServer: String,
    val createdAt: Instant
)

data class ImportedBalance(
    val uuid: UUID,
    val username: String,
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
    MIGRATION
}

data class BalanceSyncRecord(
    val serverId: String,
    val uuid: UUID,
    val username: String,
    val balance: BigDecimal,
    val version: Long,
    val updatedAt: Instant
)

class InsufficientFundsException(
    val currentBalance: BigDecimal,
    val attempted: BigDecimal
) : IllegalStateException(
    "Insufficient funds. current=$currentBalance attempted=$attempted"
)
