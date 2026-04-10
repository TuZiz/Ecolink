package ym.ecolink.storage

import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.AccountRecord
import ym.ecolink.economy.ImportedBalance
import ym.ecolink.economy.LedgerAction
import ym.ecolink.economy.LedgerEntry
import ym.ecolink.economy.RechargeReceipt
import ym.ecolink.economy.TransferReceipt
import ym.ecolink.migration.SourceMigrationReport
import java.math.BigDecimal
import java.util.UUID

interface EconomyRepository {
    fun initialize()

    fun getOrCreate(
        identity: AccountIdentity,
        currencyKey: String,
        startingBalance: BigDecimal,
        serverId: String
    ): AccountRecord

    fun findIdentityByUuid(uuid: UUID): AccountIdentity?

    fun findIdentityByUsername(username: String): AccountIdentity?

    fun adjustBalance(
        identity: AccountIdentity,
        currencyKey: String,
        delta: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String,
        action: LedgerAction,
        requireSufficient: Boolean
    ): AccountRecord

    fun setBalance(
        identity: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): AccountRecord

    fun applyRecharge(
        identity: AccountIdentity,
        currencyKey: String,
        transactionId: String,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): RechargeReceipt

    fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): TransferReceipt

    fun importBalances(
        entries: List<ImportedBalance>,
        overwrite: Boolean,
        source: String,
        serverId: String
    ): SourceMigrationReport

    fun findTopAccounts(
        currencyKey: String,
        limit: Int,
        offset: Int
    ): List<AccountRecord>

    fun findLedgerEntries(
        accountUuid: UUID,
        currencyKey: String,
        limit: Int,
        offset: Int
    ): List<LedgerEntry>
}
