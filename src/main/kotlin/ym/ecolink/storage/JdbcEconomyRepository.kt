package ym.ecolink.storage

import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.AccountRecord
import ym.ecolink.economy.ImportedBalance
import ym.ecolink.economy.InsufficientFundsException
import ym.ecolink.economy.LedgerEntry
import ym.ecolink.economy.LedgerAction
import ym.ecolink.economy.TransferReceipt
import ym.ecolink.migration.SourceMigrationReport
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcEconomyRepository(
    private val dataSource: DataSource,
    tablePrefix: String,
    private val scale: Int
) : EconomyRepository {

    private val accountsTable = "${tablePrefix}accounts"
    private val ledgerTable = "${tablePrefix}ledger"

    override fun initialize() {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $accountsTable (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(32) NOT NULL,
                        balance DECIMAL(20, $scale) NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL,
                        last_server VARCHAR(64)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $ledgerTable (
                        id VARCHAR(36) PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        counterparty_uuid VARCHAR(36),
                        action VARCHAR(32) NOT NULL,
                        amount DECIMAL(20, $scale) NOT NULL,
                        balance_after DECIMAL(20, $scale) NOT NULL,
                        actor VARCHAR(64),
                        reason VARCHAR(128),
                        source_server VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP NOT NULL
                    )
                    """.trimIndent()
                )
                runCatching {
                    statement.execute("CREATE INDEX ecolink_accounts_username_idx ON $accountsTable (username)")
                }
            }
            connection.autoCommit = false
        }
    }

    override fun getOrCreate(
        identity: AccountIdentity,
        startingBalance: BigDecimal,
        serverId: String
    ): AccountRecord {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val existing = lockAccount(connection, identity.uuid)
                if (existing == null) {
                    createAccount(connection, identity, startingBalance, serverId)
                } else if (!existing.username.equals(identity.username, ignoreCase = true)) {
                    updateUsername(connection, existing, identity, serverId)
                } else {
                    existing
                }
            }
        }
    }

    override fun findByUuid(uuid: UUID): AccountRecord? {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            return findAccountByUuid(connection, uuid, false)
        }
    }

    override fun findByUsername(username: String): AccountRecord? {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            return connection.prepareStatement(
                "SELECT uuid, username, balance, version, updated_at FROM $accountsTable WHERE LOWER(username) = LOWER(?) ORDER BY updated_at DESC"
            ).use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        mapAccount(
                            resultSet.getString("uuid"),
                            resultSet.getString("username"),
                            resultSet.getBigDecimal("balance"),
                            resultSet.getLong("version"),
                            resultSet.getTimestamp("updated_at")
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    override fun adjustBalance(
        identity: AccountIdentity,
        delta: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String,
        action: LedgerAction,
        requireSufficient: Boolean
    ): AccountRecord {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val current = lockAccount(connection, identity.uuid)
                    ?: createAccount(connection, identity, startingBalance, serverId)
                val nextBalance = normalize(current.balance.add(delta))
                if (requireSufficient && nextBalance.signum() < 0) {
                    throw InsufficientFundsException(current.balance, delta.abs())
                }
                val updated = writeBalance(connection, current, identity, nextBalance, serverId)
                appendLedger(connection, updated, null, action, delta, actor, reason, serverId)
                updated
            }
        }
    }

    override fun setBalance(
        identity: AccountIdentity,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): AccountRecord {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val current = lockAccount(connection, identity.uuid)
                    ?: createAccount(connection, identity, startingBalance, serverId)
                val updated = writeBalance(connection, current, identity, amount, serverId)
                appendLedger(connection, updated, null, LedgerAction.SET, amount, actor, reason, serverId)
                updated
            }
        }
    }

    override fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): TransferReceipt {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val ordered = listOf(source, target).sortedBy { it.uuid.toString() }
                val locked = mutableMapOf<UUID, AccountRecord>()
                ordered.forEach { identity ->
                    val record = lockAccount(connection, identity.uuid)
                        ?: createAccount(connection, identity, startingBalance, serverId)
                    locked[identity.uuid] = record
                }

                val sourceRecord = locked.getValue(source.uuid)
                val targetRecord = locked.getValue(target.uuid)
                val newSourceBalance = normalize(sourceRecord.balance.subtract(amount))
                if (newSourceBalance.signum() < 0) {
                    throw InsufficientFundsException(sourceRecord.balance, amount)
                }

                val updatedSource = writeBalance(connection, sourceRecord, source, newSourceBalance, serverId)
                val updatedTarget = writeBalance(
                    connection,
                    targetRecord,
                    target,
                    normalize(targetRecord.balance.add(amount)),
                    serverId
                )
                appendLedger(connection, updatedSource, target.uuid, LedgerAction.TRANSFER_OUT, amount.negate(), actor, reason, serverId)
                appendLedger(connection, updatedTarget, source.uuid, LedgerAction.TRANSFER_IN, amount, actor, reason, serverId)
                TransferReceipt(updatedSource, updatedTarget)
            }
        }
    }

    override fun importBalances(
        entries: List<ImportedBalance>,
        overwrite: Boolean,
        source: String,
        serverId: String
    ): SourceMigrationReport {
        var inserted = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            transaction(connection) {
                entries.forEach { imported ->
                    val savepoint = connection.setSavepoint()
                    try {
                        val current = lockAccount(connection, imported.uuid)
                        if (current == null) {
                            val created = createAccount(connection, imported.toIdentity(), imported.balance, serverId)
                            appendLedger(
                                connection,
                                created,
                                null,
                                LedgerAction.MIGRATION,
                                imported.balance,
                                source,
                                "Imported from $source",
                                serverId
                            )
                            inserted++
                        } else if (overwrite) {
                            val rewritten = writeBalance(connection, current, imported.toIdentity(), imported.balance, serverId)
                            appendLedger(
                                connection,
                                rewritten,
                                null,
                                LedgerAction.MIGRATION,
                                imported.balance,
                                source,
                                "Imported from $source (overwrite)",
                                serverId
                            )
                            updated++
                        } else {
                            skipped++
                        }
                    } catch (error: Throwable) {
                        connection.rollback(savepoint)
                        failed++
                        if (errors.size < 5) {
                            errors += "${imported.username}: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                }
            }
        }

        return SourceMigrationReport(
            source = source,
            discovered = entries.size,
            inserted = inserted,
            updated = updated,
            skipped = skipped,
            failed = failed,
            sampleErrors = errors
        )
    }

    override fun findTopAccounts(limit: Int, offset: Int): List<AccountRecord> {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                "SELECT uuid, username, balance, version, updated_at FROM $accountsTable ORDER BY balance DESC, username ASC LIMIT ? OFFSET ?"
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<AccountRecord>()
                    while (resultSet.next()) {
                        results += mapAccount(
                            resultSet.getString("uuid"),
                            resultSet.getString("username"),
                            resultSet.getBigDecimal("balance"),
                            resultSet.getLong("version"),
                            resultSet.getTimestamp("updated_at")
                        )
                    }
                    return results
                }
            }
        }
    }

    override fun findLedgerEntries(accountUuid: UUID, limit: Int, offset: Int): List<LedgerEntry> {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                """
                SELECT id, account_uuid, counterparty_uuid, action, amount, balance_after, actor, reason, source_server, created_at
                FROM $ledgerTable
                WHERE account_uuid = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, accountUuid.toString())
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<LedgerEntry>()
                    while (resultSet.next()) {
                        results += LedgerEntry(
                            id = UUID.fromString(resultSet.getString("id")),
                            accountUuid = UUID.fromString(resultSet.getString("account_uuid")),
                            counterpartyUuid = resultSet.getString("counterparty_uuid")?.let(UUID::fromString),
                            action = LedgerAction.valueOf(resultSet.getString("action")),
                            amount = normalize(resultSet.getBigDecimal("amount")),
                            balanceAfter = normalize(resultSet.getBigDecimal("balance_after")),
                            actor = resultSet.getString("actor"),
                            reason = resultSet.getString("reason"),
                            sourceServer = resultSet.getString("source_server"),
                            createdAt = resultSet.getTimestamp("created_at").toInstant()
                        )
                    }
                    return results
                }
            }
        }
    }

    private fun createAccount(
        connection: Connection,
        identity: AccountIdentity,
        balance: BigDecimal,
        serverId: String
    ): AccountRecord {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """
            INSERT INTO $accountsTable (uuid, username, balance, version, created_at, updated_at, last_server)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identity.uuid.toString())
            statement.setString(2, identity.username)
            statement.setBigDecimal(3, normalize(balance))
            statement.setLong(4, 0L)
            statement.setTimestamp(5, now)
            statement.setTimestamp(6, now)
            statement.setString(7, serverId)
            try {
                statement.executeUpdate()
            } catch (error: SQLException) {
                if (!isDuplicateKey(error)) {
                    throw error
                }
            }
        }
        return findAccountByUuid(connection, identity.uuid, true) ?: error("Failed to create account ${identity.uuid}")
    }

    private fun updateUsername(
        connection: Connection,
        current: AccountRecord,
        identity: AccountIdentity,
        serverId: String
    ): AccountRecord {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            "UPDATE $accountsTable SET username = ?, version = ?, updated_at = ?, last_server = ? WHERE uuid = ?"
        ).use { statement ->
            statement.setString(1, identity.username)
            statement.setLong(2, current.version + 1)
            statement.setTimestamp(3, now)
            statement.setString(4, serverId)
            statement.setString(5, identity.uuid.toString())
            statement.executeUpdate()
        }
        return AccountRecord(identity.uuid, identity.username, current.balance, current.version + 1, now.toInstant())
    }

    private fun writeBalance(
        connection: Connection,
        current: AccountRecord,
        identity: AccountIdentity,
        balance: BigDecimal,
        serverId: String
    ): AccountRecord {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            "UPDATE $accountsTable SET username = ?, balance = ?, version = ?, updated_at = ?, last_server = ? WHERE uuid = ?"
        ).use { statement ->
            statement.setString(1, identity.username)
            statement.setBigDecimal(2, normalize(balance))
            statement.setLong(3, current.version + 1)
            statement.setTimestamp(4, now)
            statement.setString(5, serverId)
            statement.setString(6, identity.uuid.toString())
            statement.executeUpdate()
        }
        return AccountRecord(identity.uuid, identity.username, normalize(balance), current.version + 1, now.toInstant())
    }

    private fun appendLedger(
        connection: Connection,
        account: AccountRecord,
        counterpartyUuid: UUID?,
        action: LedgerAction,
        amount: BigDecimal,
        actor: String,
        reason: String,
        serverId: String
    ) {
        connection.prepareStatement(
            """
            INSERT INTO $ledgerTable (id, account_uuid, counterparty_uuid, action, amount, balance_after, actor, reason, source_server, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, account.uuid.toString())
            statement.setString(3, counterpartyUuid?.toString())
            statement.setString(4, action.name)
            statement.setBigDecimal(5, normalize(amount))
            statement.setBigDecimal(6, normalize(account.balance))
            statement.setString(7, actor)
            statement.setString(8, reason.take(128))
            statement.setString(9, serverId)
            statement.setTimestamp(10, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun lockAccount(connection: Connection, uuid: UUID): AccountRecord? {
        return findAccountByUuid(connection, uuid, true)
    }

    private fun findAccountByUuid(connection: Connection, uuid: UUID, forUpdate: Boolean): AccountRecord? {
        val sql = buildString {
            append("SELECT uuid, username, balance, version, updated_at FROM $accountsTable WHERE uuid = ?")
            if (forUpdate) {
                append(" FOR UPDATE")
            }
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return mapAccount(
                    resultSet.getString("uuid"),
                    resultSet.getString("username"),
                    resultSet.getBigDecimal("balance"),
                    resultSet.getLong("version"),
                    resultSet.getTimestamp("updated_at")
                )
            }
        }
    }

    private fun mapAccount(
        uuid: String,
        username: String,
        balance: BigDecimal,
        version: Long,
        updatedAt: Timestamp
    ): AccountRecord {
        return AccountRecord(
            uuid = UUID.fromString(uuid),
            username = username,
            balance = normalize(balance),
            version = version,
            updatedAt = updatedAt.toInstant()
        )
    }

    private fun normalize(amount: BigDecimal): BigDecimal {
        return amount.setScale(scale, RoundingMode.HALF_UP)
    }

    private fun <T> transaction(connection: Connection, action: () -> T): T {
        try {
            val result = action()
            connection.commit()
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun isDuplicateKey(error: SQLException): Boolean {
        return error.sqlState == "23505" || error.sqlState == "23000" || error.errorCode == 1062
    }
}
