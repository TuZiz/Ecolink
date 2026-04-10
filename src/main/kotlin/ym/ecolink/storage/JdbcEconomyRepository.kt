package ym.ecolink.storage

import ym.ecolink.config.PluginSettings
import ym.ecolink.economy.AccountIdentity
import ym.ecolink.economy.AccountRecord
import ym.ecolink.economy.CurrencyDeletionSummary
import ym.ecolink.economy.ImportedBalance
import ym.ecolink.economy.InsufficientFundsException
import ym.ecolink.economy.LedgerAction
import ym.ecolink.economy.LedgerEntry
import ym.ecolink.economy.RechargeReceipt
import ym.ecolink.economy.TransferReceipt
import ym.ecolink.migration.SourceMigrationReport
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcEconomyRepository(
    private val dataSource: DataSource,
    tablePrefix: String,
    private val settings: PluginSettings
) : EconomyRepository {

    private val accountsTable = "${tablePrefix}accounts"
    private val balancesTable = "${tablePrefix}account_balances"
    private val ledgerTable = "${tablePrefix}ledger"
    private val rechargesTable = "${tablePrefix}idempotent_recharges"

    private val accountsUsernameIndex = "${tablePrefix}accounts_username_idx"
    private val balancesCurrencyIndex = "${tablePrefix}balances_currency_idx"
    private val ledgerAccountIndex = "${tablePrefix}ledger_account_idx"
    private val rechargeAccountIndex = "${tablePrefix}recharge_account_idx"

    override fun initialize() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            transaction(connection) {
                ensureAccountsTable(connection)
                ensureBalancesTable(connection)
                ensureLedgerTable(connection)
                ensureRechargeTable(connection)
                ensureLedgerColumns(connection)
                migrateLegacyBalances(connection)
                createIndexes(connection)
            }
        }
    }

    override fun getOrCreate(
        identity: AccountIdentity,
        currencyKey: String,
        startingBalance: BigDecimal,
        serverId: String
    ): AccountRecord {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                ensureBalance(connection, identity, currencyKey, startingBalance, serverId, touchIdentity = false)
            }
        }
    }

    override fun findIdentityByUuid(uuid: UUID): AccountIdentity? {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            return findIdentityByUuid(connection, uuid, false)
        }
    }

    override fun findIdentityByUsername(username: String): AccountIdentity? {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                """
                SELECT uuid, username
                FROM $accountsTable
                WHERE LOWER(username) = LOWER(?)
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        AccountIdentity(
                            uuid = UUID.fromString(resultSet.getString("uuid")),
                            username = resultSet.getString("username")
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
        currencyKey: String,
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
                val current = ensureBalance(connection, identity, currencyKey, startingBalance, serverId, touchIdentity = true)
                val nextBalance = normalize(currencyKey, current.balance.add(delta))
                if (requireSufficient && nextBalance.signum() < 0) {
                    throw InsufficientFundsException(currencyKey, current.balance, delta.abs())
                }
                val updated = writeBalance(connection, current, identity, nextBalance, serverId)
                appendLedger(connection, updated, null, action, delta, actor, reason, serverId)
                updated
            }
        }
    }

    override fun setBalance(
        identity: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): AccountRecord {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val current = ensureBalance(connection, identity, currencyKey, startingBalance, serverId, touchIdentity = true)
                val updated = writeBalance(connection, current, identity, amount, serverId)
                appendLedger(connection, updated, null, LedgerAction.SET, normalize(currencyKey, amount), actor, reason, serverId)
                updated
            }
        }
    }

    override fun applyRecharge(
        identity: AccountIdentity,
        currencyKey: String,
        transactionId: String,
        amount: BigDecimal,
        startingBalance: BigDecimal,
        serverId: String,
        actor: String,
        reason: String
    ): RechargeReceipt {
        val cleanTransactionId = transactionId.trim()
        require(cleanTransactionId.isNotEmpty()) { "Transaction id cannot be blank." }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val existing = findRecharge(connection, cleanTransactionId, forUpdate = true)
                if (existing != null) {
                    verifyRechargeCollision(existing, identity, currencyKey, amount)
                    val accountIdentity = findIdentityByUuid(connection, existing.accountUuid, false)
                        ?: AccountIdentity(existing.accountUuid, identity.username)
                    return@transaction existing.toReceipt(accountIdentity)
                }

                val current = ensureBalance(connection, identity, currencyKey, startingBalance, serverId, touchIdentity = true)
                val updated = writeBalance(connection, current, identity, current.balance.add(amount), serverId)
                appendLedger(
                    connection = connection,
                    account = updated,
                    counterpartyUuid = null,
                    action = LedgerAction.RECHARGE,
                    amount = amount,
                    actor = actor,
                    reason = reason,
                    serverId = serverId,
                    idempotencyKey = cleanTransactionId
                )
                val receipt = RechargeReceipt(
                    transactionId = cleanTransactionId,
                    record = updated,
                    amount = normalize(currencyKey, amount),
                    duplicate = false,
                    actor = actor,
                    reason = reason,
                    sourceServer = serverId,
                    processedAt = updated.updatedAt
                )
                insertRecharge(connection, receipt)
                receipt
            }
        }
    }

    override fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
        currencyKey: String,
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
                val locked = linkedMapOf<UUID, AccountRecord>()
                ordered.forEach { identity ->
                    locked[identity.uuid] = ensureBalance(
                        connection = connection,
                        identity = identity,
                        currencyKey = currencyKey,
                        startingBalance = startingBalance,
                        serverId = serverId,
                        touchIdentity = true
                    )
                }

                val sourceRecord = locked.getValue(source.uuid)
                val targetRecord = locked.getValue(target.uuid)
                val newSourceBalance = normalize(currencyKey, sourceRecord.balance.subtract(amount))
                if (newSourceBalance.signum() < 0) {
                    throw InsufficientFundsException(currencyKey, sourceRecord.balance, amount)
                }

                val updatedSource = writeBalance(connection, sourceRecord, source, newSourceBalance, serverId)
                val updatedTarget = writeBalance(
                    connection = connection,
                    current = targetRecord,
                    identity = target,
                    balance = targetRecord.balance.add(amount),
                    serverId = serverId
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
                        val current = findBalanceByUuid(connection, imported.uuid, imported.currencyKey, forUpdate = true)
                        if (current == null) {
                            val created = ensureBalance(
                                connection = connection,
                                identity = imported.toIdentity(),
                                currencyKey = imported.currencyKey,
                                startingBalance = imported.balance,
                                serverId = serverId,
                                touchIdentity = true
                            )
                            appendLedger(connection, created, null, LedgerAction.MIGRATION, imported.balance, source, "Imported from $source", serverId)
                            inserted++
                        } else if (overwrite) {
                            val rewritten = writeBalance(
                                connection = connection,
                                current = current,
                                identity = imported.toIdentity(),
                                balance = imported.balance,
                                serverId = serverId
                            )
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
                            errors += "${imported.username}/${imported.currencyKey}: ${error.message ?: error.javaClass.simpleName}"
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

    override fun findTopAccounts(currencyKey: String, limit: Int, offset: Int): List<AccountRecord> {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                """
                SELECT a.uuid, a.username, b.currency_key, b.balance, b.version, b.updated_at
                FROM $balancesTable b
                INNER JOIN $accountsTable a ON a.uuid = b.account_uuid
                WHERE b.currency_key = ?
                ORDER BY b.balance DESC, a.username ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, currencyKey)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<AccountRecord>()
                    while (resultSet.next()) {
                        results += mapAccount(resultSet)
                    }
                    return results
                }
            }
        }
    }

    override fun findLedgerEntries(accountUuid: UUID, currencyKey: String, limit: Int, offset: Int): List<LedgerEntry> {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                """
                SELECT id, account_uuid, currency_key, counterparty_uuid, action, amount, balance_after,
                       actor, reason, source_server, idempotency_key, created_at
                FROM $ledgerTable
                WHERE account_uuid = ? AND currency_key = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, accountUuid.toString())
                statement.setString(2, currencyKey)
                statement.setInt(3, limit)
                statement.setInt(4, offset)
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<LedgerEntry>()
                    while (resultSet.next()) {
                        val ledgerCurrency = resultSet.getString("currency_key")
                        results += LedgerEntry(
                            id = UUID.fromString(resultSet.getString("id")),
                            accountUuid = UUID.fromString(resultSet.getString("account_uuid")),
                            currencyKey = ledgerCurrency,
                            counterpartyUuid = resultSet.getString("counterparty_uuid")?.let(UUID::fromString),
                            action = LedgerAction.valueOf(resultSet.getString("action")),
                            amount = normalize(ledgerCurrency, resultSet.getBigDecimal("amount")),
                            balanceAfter = normalize(ledgerCurrency, resultSet.getBigDecimal("balance_after")),
                            actor = resultSet.getString("actor"),
                            reason = resultSet.getString("reason"),
                            sourceServer = resultSet.getString("source_server"),
                            idempotencyKey = resultSet.getString("idempotency_key"),
                            createdAt = resultSet.getTimestamp("created_at").toInstant()
                        )
                    }
                    return results
                }
            }
        }
    }

    override fun deleteCurrencyData(currencyKey: String): CurrencyDeletionSummary {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return transaction(connection) {
                val normalizedCurrency = settings.requireCurrency(currencyKey).key
                val deletedRecharges = deleteByCurrency(connection, rechargesTable, normalizedCurrency)
                val deletedLedger = deleteByCurrency(connection, ledgerTable, normalizedCurrency)
                val deletedBalances = deleteByCurrency(connection, balancesTable, normalizedCurrency)
                CurrencyDeletionSummary(
                    currencyKey = normalizedCurrency,
                    balancesDeleted = deletedBalances,
                    ledgerDeleted = deletedLedger,
                    rechargesDeleted = deletedRecharges
                )
            }
        }
    }

    private fun ensureAccountsTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $accountsTable (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    last_server VARCHAR(64)
                )
                """.trimIndent()
            )
        }
    }

    private fun ensureBalancesTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $balancesTable (
                    account_uuid VARCHAR(36) NOT NULL,
                    currency_key VARCHAR(32) NOT NULL,
                    balance DECIMAL(20, ${settings.maxBalanceScale}) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    last_server VARCHAR(64),
                    PRIMARY KEY (account_uuid, currency_key)
                )
                """.trimIndent()
            )
        }
    }

    private fun ensureLedgerTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $ledgerTable (
                    id VARCHAR(36) PRIMARY KEY,
                    account_uuid VARCHAR(36) NOT NULL,
                    currency_key VARCHAR(32) NOT NULL,
                    counterparty_uuid VARCHAR(36),
                    action VARCHAR(32) NOT NULL,
                    amount DECIMAL(20, ${settings.maxBalanceScale}) NOT NULL,
                    balance_after DECIMAL(20, ${settings.maxBalanceScale}) NOT NULL,
                    actor VARCHAR(64),
                    reason VARCHAR(128),
                    source_server VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(128),
                    created_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun ensureRechargeTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $rechargesTable (
                    transaction_id VARCHAR(128) PRIMARY KEY,
                    account_uuid VARCHAR(36) NOT NULL,
                    currency_key VARCHAR(32) NOT NULL,
                    amount DECIMAL(20, ${settings.maxBalanceScale}) NOT NULL,
                    balance_after DECIMAL(20, ${settings.maxBalanceScale}) NOT NULL,
                    version BIGINT NOT NULL,
                    actor VARCHAR(64),
                    reason VARCHAR(128),
                    source_server VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun ensureLedgerColumns(connection: Connection) {
        if (!hasColumn(connection, ledgerTable, "currency_key")) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE $ledgerTable ADD COLUMN currency_key VARCHAR(32) NOT NULL DEFAULT '${settings.defaultCurrencyKey}'")
            }
        }
        if (!hasColumn(connection, ledgerTable, "idempotency_key")) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE $ledgerTable ADD COLUMN idempotency_key VARCHAR(128)")
            }
        }
    }

    private fun migrateLegacyBalances(connection: Connection) {
        if (!hasTable(connection, accountsTable) || !hasColumn(connection, accountsTable, "balance")) {
            return
        }
        val hasVersion = hasColumn(connection, accountsTable, "version")
        val hasCreatedAt = hasColumn(connection, accountsTable, "created_at")
        val hasUpdatedAt = hasColumn(connection, accountsTable, "updated_at")
        val hasLastServer = hasColumn(connection, accountsTable, "last_server")

        val selectSql = buildString {
            append("SELECT uuid, username, balance")
            append(if (hasVersion) ", version" else ", 0 AS version")
            append(if (hasCreatedAt) ", created_at" else ", NULL AS created_at")
            append(if (hasUpdatedAt) ", updated_at" else ", NULL AS updated_at")
            append(if (hasLastServer) ", last_server" else ", NULL AS last_server")
            append(" FROM $accountsTable")
        }

        connection.prepareStatement(selectSql).use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    val uuid = UUID.fromString(resultSet.getString("uuid"))
                    if (findBalanceByUuid(connection, uuid, settings.defaultCurrencyKey, forUpdate = true) != null) {
                        continue
                    }
                    val balance = normalize(settings.defaultCurrencyKey, resultSet.getBigDecimal("balance") ?: BigDecimal.ZERO)
                    val version = resultSet.getLong("version")
                    val createdAt = resultSet.getTimestamp("created_at") ?: Timestamp.from(Instant.now())
                    val updatedAt = resultSet.getTimestamp("updated_at") ?: createdAt
                    val lastServer = resultSet.getString("last_server")

                    connection.prepareStatement(
                        """
                        INSERT INTO $balancesTable (account_uuid, currency_key, balance, version, created_at, updated_at, last_server)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, uuid.toString())
                        insert.setString(2, settings.defaultCurrencyKey)
                        insert.setBigDecimal(3, balance)
                        insert.setLong(4, version)
                        insert.setTimestamp(5, createdAt)
                        insert.setTimestamp(6, updatedAt)
                        insert.setString(7, lastServer)
                        insert.executeUpdate()
                    }
                }
            }
        }
    }

    private fun createIndexes(connection: Connection) {
        createIndex(connection, accountsUsernameIndex, "CREATE INDEX $accountsUsernameIndex ON $accountsTable (username)")
        createIndex(connection, balancesCurrencyIndex, "CREATE INDEX $balancesCurrencyIndex ON $balancesTable (currency_key, balance)")
        createIndex(connection, ledgerAccountIndex, "CREATE INDEX $ledgerAccountIndex ON $ledgerTable (account_uuid, currency_key, created_at)")
        createIndex(connection, rechargeAccountIndex, "CREATE INDEX $rechargeAccountIndex ON $rechargesTable (account_uuid, currency_key, created_at)")
    }

    private fun createIndex(connection: Connection, indexName: String, sql: String) {
        runCatching {
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }.onFailure { error ->
            if (error !is SQLException || !isDuplicateKey(error, indexName)) {
                throw error
            }
        }
    }

    private fun ensureBalance(
        connection: Connection,
        identity: AccountIdentity,
        currencyKey: String,
        startingBalance: BigDecimal,
        serverId: String,
        touchIdentity: Boolean
    ): AccountRecord {
        val ensuredIdentity = ensureIdentity(connection, identity, serverId, touchIdentity)
        val current = findBalanceByUuid(connection, identity.uuid, currencyKey, forUpdate = true)
        if (current != null) {
            return current.copy(username = ensuredIdentity.username)
        }
        return createBalance(connection, ensuredIdentity, currencyKey, startingBalance, serverId)
    }

    private fun ensureIdentity(
        connection: Connection,
        identity: AccountIdentity,
        serverId: String,
        touch: Boolean
    ): AccountIdentity {
        val existing = findIdentityByUuid(connection, identity.uuid, forUpdate = true)
        if (existing == null) {
            return createIdentity(connection, identity, serverId)
        }
        if (!existing.username.equals(identity.username, ignoreCase = true) || touch) {
            return writeIdentity(connection, identity, serverId)
        }
        return existing
    }

    private fun createIdentity(connection: Connection, identity: AccountIdentity, serverId: String): AccountIdentity {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """
            INSERT INTO $accountsTable (uuid, username, created_at, updated_at, last_server)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identity.uuid.toString())
            statement.setString(2, identity.username)
            statement.setTimestamp(3, now)
            statement.setTimestamp(4, now)
            statement.setString(5, serverId)
            try {
                statement.executeUpdate()
            } catch (error: SQLException) {
                if (!isDuplicateKey(error)) {
                    throw error
                }
            }
        }
        return findIdentityByUuid(connection, identity.uuid, forUpdate = true) ?: identity
    }

    private fun writeIdentity(connection: Connection, identity: AccountIdentity, serverId: String): AccountIdentity {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """
            UPDATE $accountsTable
            SET username = ?, updated_at = ?, last_server = ?
            WHERE uuid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identity.username)
            statement.setTimestamp(2, now)
            statement.setString(3, serverId)
            statement.setString(4, identity.uuid.toString())
            statement.executeUpdate()
        }
        return identity
    }

    private fun createBalance(
        connection: Connection,
        identity: AccountIdentity,
        currencyKey: String,
        balance: BigDecimal,
        serverId: String
    ): AccountRecord {
        val normalized = normalize(currencyKey, balance)
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """
            INSERT INTO $balancesTable (account_uuid, currency_key, balance, version, created_at, updated_at, last_server)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identity.uuid.toString())
            statement.setString(2, currencyKey)
            statement.setBigDecimal(3, normalized)
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
        return findBalanceByUuid(connection, identity.uuid, currencyKey, forUpdate = true)
            ?: error("Failed to create balance row for ${identity.uuid}/$currencyKey")
    }

    private fun writeBalance(
        connection: Connection,
        current: AccountRecord,
        identity: AccountIdentity,
        balance: BigDecimal,
        serverId: String
    ): AccountRecord {
        val normalized = normalize(current.currencyKey, balance)
        val now = Timestamp.from(Instant.now())
        writeIdentity(connection, identity, serverId)
        connection.prepareStatement(
            """
            UPDATE $balancesTable
            SET balance = ?, version = ?, updated_at = ?, last_server = ?
            WHERE account_uuid = ? AND currency_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setBigDecimal(1, normalized)
            statement.setLong(2, current.version + 1)
            statement.setTimestamp(3, now)
            statement.setString(4, serverId)
            statement.setString(5, current.uuid.toString())
            statement.setString(6, current.currencyKey)
            statement.executeUpdate()
        }
        return current.copy(
            username = identity.username,
            balance = normalized,
            version = current.version + 1,
            updatedAt = now.toInstant()
        )
    }

    private fun appendLedger(
        connection: Connection,
        account: AccountRecord,
        counterpartyUuid: UUID?,
        action: LedgerAction,
        amount: BigDecimal,
        actor: String,
        reason: String,
        serverId: String,
        idempotencyKey: String? = null
    ) {
        connection.prepareStatement(
            """
            INSERT INTO $ledgerTable (
                id, account_uuid, currency_key, counterparty_uuid, action, amount, balance_after,
                actor, reason, source_server, idempotency_key, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, account.uuid.toString())
            statement.setString(3, account.currencyKey)
            statement.setString(4, counterpartyUuid?.toString())
            statement.setString(5, action.name)
            statement.setBigDecimal(6, normalize(account.currencyKey, amount))
            statement.setBigDecimal(7, normalize(account.currencyKey, account.balance))
            statement.setString(8, truncate(actor, 64))
            statement.setString(9, truncate(reason, 128))
            statement.setString(10, serverId)
            statement.setString(11, idempotencyKey?.take(128))
            statement.setTimestamp(12, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun insertRecharge(connection: Connection, receipt: RechargeReceipt) {
        connection.prepareStatement(
            """
            INSERT INTO $rechargesTable (
                transaction_id, account_uuid, currency_key, amount, balance_after, version,
                actor, reason, source_server, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, receipt.transactionId.take(128))
            statement.setString(2, receipt.record.uuid.toString())
            statement.setString(3, receipt.record.currencyKey)
            statement.setBigDecimal(4, normalize(receipt.record.currencyKey, receipt.amount))
            statement.setBigDecimal(5, normalize(receipt.record.currencyKey, receipt.record.balance))
            statement.setLong(6, receipt.record.version)
            statement.setString(7, receipt.actor?.let { truncate(it, 64) })
            statement.setString(8, receipt.reason?.let { truncate(it, 128) })
            statement.setString(9, receipt.sourceServer)
            statement.setTimestamp(10, Timestamp.from(receipt.processedAt))
            statement.executeUpdate()
        }
    }

    private fun verifyRechargeCollision(
        existing: RechargeRow,
        identity: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal
    ) {
        val normalizedAmount = normalize(currencyKey, amount)
        require(existing.accountUuid == identity.uuid) {
            "Transaction id ${existing.transactionId} already belongs to another account."
        }
        require(existing.currencyKey.equals(currencyKey, ignoreCase = true)) {
            "Transaction id ${existing.transactionId} already belongs to currency ${existing.currencyKey}."
        }
        require(existing.amount.compareTo(normalizedAmount) == 0) {
            "Transaction id ${existing.transactionId} already recorded a different amount."
        }
    }

    private fun findIdentityByUuid(connection: Connection, uuid: UUID, forUpdate: Boolean): AccountIdentity? {
        val sql = buildString {
            append("SELECT uuid, username FROM $accountsTable WHERE uuid = ?")
            if (forUpdate) {
                append(" FOR UPDATE")
            }
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    AccountIdentity(
                        uuid = UUID.fromString(resultSet.getString("uuid")),
                        username = resultSet.getString("username")
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun findBalanceByUuid(
        connection: Connection,
        uuid: UUID,
        currencyKey: String,
        forUpdate: Boolean
    ): AccountRecord? {
        val sql = buildString {
            append(
                """
                SELECT a.uuid, a.username, b.currency_key, b.balance, b.version, b.updated_at
                FROM $balancesTable b
                INNER JOIN $accountsTable a ON a.uuid = b.account_uuid
                WHERE a.uuid = ? AND b.currency_key = ?
                """.trimIndent()
            )
            if (forUpdate) {
                append(" FOR UPDATE")
            }
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, uuid.toString())
            statement.setString(2, currencyKey)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    mapAccount(resultSet)
                } else {
                    null
                }
            }
        }
    }

    private fun findRecharge(connection: Connection, transactionId: String, forUpdate: Boolean): RechargeRow? {
        val sql = buildString {
            append(
                """
                SELECT transaction_id, account_uuid, currency_key, amount, balance_after, version,
                       actor, reason, source_server, created_at
                FROM $rechargesTable
                WHERE transaction_id = ?
                """.trimIndent()
            )
            if (forUpdate) {
                append(" FOR UPDATE")
            }
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, transactionId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    val rechargeCurrency = resultSet.getString("currency_key")
                    RechargeRow(
                        transactionId = resultSet.getString("transaction_id"),
                        accountUuid = UUID.fromString(resultSet.getString("account_uuid")),
                        currencyKey = rechargeCurrency,
                        amount = normalize(rechargeCurrency, resultSet.getBigDecimal("amount")),
                        balanceAfter = normalize(rechargeCurrency, resultSet.getBigDecimal("balance_after")),
                        version = resultSet.getLong("version"),
                        actor = resultSet.getString("actor"),
                        reason = resultSet.getString("reason"),
                        sourceServer = resultSet.getString("source_server"),
                        createdAt = resultSet.getTimestamp("created_at").toInstant()
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun mapAccount(resultSet: java.sql.ResultSet): AccountRecord {
        val currencyKey = resultSet.getString("currency_key")
        return AccountRecord(
            uuid = UUID.fromString(resultSet.getString("uuid")),
            username = resultSet.getString("username"),
            currencyKey = currencyKey,
            balance = normalize(currencyKey, resultSet.getBigDecimal("balance")),
            version = resultSet.getLong("version"),
            updatedAt = resultSet.getTimestamp("updated_at").toInstant()
        )
    }

    private fun hasTable(connection: Connection, tableName: String): Boolean {
        return runCatching {
            connection.prepareStatement("SELECT 1 FROM $tableName WHERE 1 = 0").use { }
            true
        }.getOrDefault(false)
    }

    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean {
        return runCatching {
            connection.prepareStatement("SELECT $columnName FROM $tableName WHERE 1 = 0").use { }
            true
        }.getOrDefault(false)
    }

    private fun deleteByCurrency(connection: Connection, tableName: String, currencyKey: String): Int {
        connection.prepareStatement("DELETE FROM $tableName WHERE currency_key = ?").use { statement ->
            statement.setString(1, currencyKey)
            return statement.executeUpdate()
        }
    }

    private fun normalize(currencyKey: String, amount: BigDecimal): BigDecimal {
        return settings.normalize(currencyKey, amount)
    }

    private fun truncate(value: String, maxLength: Int): String {
        return if (value.length <= maxLength) value else value.take(maxLength)
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

    private fun isDuplicateKey(error: SQLException, indexName: String? = null): Boolean {
        val matchesIndex = indexName?.let { error.message?.contains(it, ignoreCase = true) == true } ?: false
        return error.sqlState == "23505" ||
            error.sqlState == "23000" ||
            error.errorCode == 1062 ||
            matchesIndex
    }

    private data class RechargeRow(
        val transactionId: String,
        val accountUuid: UUID,
        val currencyKey: String,
        val amount: BigDecimal,
        val balanceAfter: BigDecimal,
        val version: Long,
        val actor: String?,
        val reason: String?,
        val sourceServer: String,
        val createdAt: Instant
    ) {
        fun toReceipt(identity: AccountIdentity): RechargeReceipt {
            return RechargeReceipt(
                transactionId = transactionId,
                record = AccountRecord(
                    uuid = accountUuid,
                    username = identity.username,
                    currencyKey = currencyKey,
                    balance = balanceAfter,
                    version = version,
                    updatedAt = createdAt
                ),
                amount = amount,
                duplicate = true,
                actor = actor,
                reason = reason,
                sourceServer = sourceServer,
                processedAt = createdAt
            )
        }
    }
}
