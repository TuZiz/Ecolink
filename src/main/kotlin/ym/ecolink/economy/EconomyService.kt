package ym.ecolink.economy

import ym.ecolink.config.PluginSettings
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.storage.EconomyRepository
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EconomyService(
    private val settings: PluginSettings,
    private val repository: EconomyRepository,
    private val dispatcher: ServerTaskDispatcher
) {

    private val cache = ConcurrentHashMap<CacheKey, CachedAccount>()
    private val nameIndex = ConcurrentHashMap<String, UUID>()

    @Volatile
    private var mutationListener: ((BalanceSyncRecord) -> Unit)? = null

    fun setMutationListener(listener: (BalanceSyncRecord) -> Unit) {
        mutationListener = listener
    }

    fun ensureAccount(identity: AccountIdentity, currencyKey: String = settings.defaultCurrencyKey): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val cached = cache[CacheKey(identity.uuid, normalizedCurrency)]
            if (cached != null && cached.isFresh()) {
                return@supplyAsync cached.record
            }
            remember(
                repository.getOrCreate(
                    identity = identity,
                    currencyKey = normalizedCurrency,
                    startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                    serverId = settings.serverId
                )
            )
        }
    }

    fun getBalance(identity: AccountIdentity, currencyKey: String = settings.defaultCurrencyKey): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val cached = cache[CacheKey(identity.uuid, normalizedCurrency)]
            if (cached != null && cached.isFresh() && cached.record.username.equals(identity.username, ignoreCase = true)) {
                return@supplyAsync cached.record
            }
            remember(
                repository.getOrCreate(
                    identity = identity,
                    currencyKey = normalizedCurrency,
                    startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                    serverId = settings.serverId
                )
            )
        }
    }

    fun resolveIdentity(selector: String): CompletableFuture<AccountIdentity?> {
        return dispatcher.supplyAsync {
            parseUuid(selector)?.let { uuid ->
                findCachedIdentity(uuid)?.let { return@supplyAsync it }
                return@supplyAsync repository.findIdentityByUuid(uuid)
            }

            val cachedUuid = nameIndex[selector.lowercase()]
            if (cachedUuid != null) {
                findCachedIdentity(cachedUuid)?.let { return@supplyAsync it }
            }

            repository.findIdentityByUsername(selector)
        }
    }

    fun addBalance(
        target: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return mutateBalance(target, currencyKey, amount, actor, reason, LedgerAction.ADD, requireSufficient = false)
    }

    fun takeBalance(
        target: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return mutateBalance(target, currencyKey, amount.negate(), actor, reason, LedgerAction.TAKE, requireSufficient = true)
    }

    fun setBalance(
        target: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val updated = remember(
                repository.setBalance(
                    identity = target,
                    currencyKey = normalizedCurrency,
                    amount = settings.normalize(normalizedCurrency, amount),
                    startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason
                )
            )
            publish(updated)
            updated
        }
    }

    fun recharge(
        target: AccountIdentity,
        currencyKey: String,
        transactionId: String,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<RechargeReceipt> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val receipt = repository.applyRecharge(
                identity = target,
                currencyKey = normalizedCurrency,
                transactionId = transactionId,
                amount = settings.normalize(normalizedCurrency, amount),
                startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                serverId = settings.serverId,
                actor = actor,
                reason = reason
            )
            val remembered = remember(receipt.record)
            if (!receipt.duplicate) {
                publish(remembered)
            }
            receipt.copy(record = remembered)
        }
    }

    fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
        currencyKey: String,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<TransferReceipt> {
        return dispatcher.supplyAsync {
            if (source.uuid == target.uuid) {
                throw IllegalArgumentException("Cannot transfer to self.")
            }
            val normalizedCurrency = requireCurrency(currencyKey)
            val receipt = repository.transfer(
                source = source,
                target = target,
                currencyKey = normalizedCurrency,
                amount = settings.normalize(normalizedCurrency, amount),
                startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                serverId = settings.serverId,
                actor = actor,
                reason = reason
            )
            val updatedSource = remember(receipt.from)
            val updatedTarget = remember(receipt.to)
            publish(updatedSource)
            publish(updatedTarget)
            TransferReceipt(updatedSource, updatedTarget)
        }
    }

    fun getTopBalances(page: Int, currencyKey: String = settings.defaultCurrencyKey): CompletableFuture<List<AccountRecord>> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val safePage = page.coerceAtLeast(1)
            val limit = settings.feature.topPageSize
            val offset = (safePage - 1) * limit
            repository.findTopAccounts(normalizedCurrency, limit, offset).map(::remember)
        }
    }

    fun getLedger(
        identity: AccountIdentity,
        page: Int,
        currencyKey: String = settings.defaultCurrencyKey
    ): CompletableFuture<List<LedgerEntry>> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val safePage = page.coerceAtLeast(1)
            val limit = settings.feature.ledgerPageSize
            val offset = (safePage - 1) * limit
            repository.findLedgerEntries(identity.uuid, normalizedCurrency, limit, offset)
        }
    }

    fun peekCached(uuid: UUID, currencyKey: String = settings.defaultCurrencyKey): AccountRecord? {
        val normalizedCurrency = settings.findCurrency(currencyKey)?.key ?: return null
        val cached = cache[CacheKey(uuid, normalizedCurrency)]
        return if (cached != null && cached.isFresh()) cached.record else null
    }

    fun peekCached(selector: String, currencyKey: String = settings.defaultCurrencyKey): AccountRecord? {
        val uuid = nameIndex[selector.lowercase()] ?: return null
        return peekCached(uuid, currencyKey)
    }

    fun rememberRemote(record: BalanceSyncRecord) {
        val normalizedCurrency = requireCurrency(record.currencyKey)
        val incoming = AccountRecord(
            uuid = record.uuid,
            username = record.username,
            currencyKey = normalizedCurrency,
            balance = settings.normalize(normalizedCurrency, record.balance),
            version = record.version,
            updatedAt = record.updatedAt
        )
        val key = CacheKey(record.uuid, normalizedCurrency)
        val current = cache[key]
        if (current == null || current.record.version <= incoming.version || !current.isFresh()) {
            remember(incoming)
        }
    }

    fun awaitAccount(identity: AccountIdentity, currencyKey: String = settings.defaultCurrencyKey): AccountRecord {
        return getBalance(identity, currencyKey).get(settings.compatibility.vault.syncTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun awaitIdentity(selector: String): AccountIdentity? {
        return resolveIdentity(selector).get(settings.compatibility.vault.syncTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun deleteCurrencyData(currencyKey: String): CompletableFuture<CurrencyDeletionSummary> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = settings.requireCurrency(currencyKey).key
            val summary = repository.deleteCurrencyData(normalizedCurrency)
            clearCurrencyCache(normalizedCurrency)
            summary
        }
    }

    private fun mutateBalance(
        target: AccountIdentity,
        currencyKey: String,
        delta: BigDecimal,
        actor: String,
        reason: String,
        action: LedgerAction,
        requireSufficient: Boolean
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val normalizedCurrency = requireCurrency(currencyKey)
            val updated = remember(
                repository.adjustBalance(
                    identity = target,
                    currencyKey = normalizedCurrency,
                    delta = settings.normalize(normalizedCurrency, delta),
                    startingBalance = settings.requireCurrency(normalizedCurrency).startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason,
                    action = action,
                    requireSufficient = requireSufficient
                )
            )
            publish(updated)
            updated
        }
    }

    private fun remember(record: AccountRecord): AccountRecord {
        cache[CacheKey(record.uuid, record.currencyKey)] = CachedAccount(
            record = record,
            expiresAt = System.currentTimeMillis() + settings.cacheTtlMillis
        )
        nameIndex[record.username.lowercase()] = record.uuid
        return record
    }

    private fun publish(record: AccountRecord) {
        mutationListener?.invoke(
            BalanceSyncRecord(
                serverId = settings.serverId,
                uuid = record.uuid,
                username = record.username,
                currencyKey = record.currencyKey,
                balance = record.balance,
                version = record.version,
                updatedAt = record.updatedAt
            )
        )
    }

    private fun findCachedIdentity(uuid: UUID): AccountIdentity? {
        return cache.entries.firstOrNull { it.key.uuid == uuid && it.value.isFresh() }?.value?.record?.toIdentity()
    }

    private fun requireCurrency(currencyKey: String): String {
        return settings.requireCurrency(currencyKey).key
    }

    private fun clearCurrencyCache(currencyKey: String) {
        cache.entries.removeIf { it.key.currencyKey == currencyKey }
    }

    private fun parseUuid(raw: String): UUID? {
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private data class CacheKey(
        val uuid: UUID,
        val currencyKey: String
    )

    private data class CachedAccount(
        val record: AccountRecord,
        val expiresAt: Long
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() <= expiresAt
    }
}
