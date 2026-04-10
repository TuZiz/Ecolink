package ym.ecolink.storage

import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.AccountRecord
import ym.ecolink.economy.ImportedBalance
import ym.ecolink.economy.LedgerEntry
import ym.ecolink.economy.LedgerAction
import ym.ecolink.economy.TransferReceipt
import ym.ecolink.migration.SourceMigrationReport
import java.math.BigDecimal
import java.util.UUID

interface EconomyRepository {
    fun initialize()

    fun getOrCreate(
        identity: AccountIdentity,
        startingBalance: BigDecimal,
        serverId: String
    ): AccountRecord

    fun findByUuid(uuid: UUID): AccountRecord?

    fun findByUsername(username: String): AccountRecord?

    fun adjustBalance(
        identity: AccountIdentity,
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
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): AccountRecord

    fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
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

    fun findTopAccounts(limit: Int, offset: Int): List<AccountRecord>

    fun findLedgerEntries(
        accountUuid: UUID,
        limit: Int,
        offset: Int
    ): List<LedgerEntry>
}
