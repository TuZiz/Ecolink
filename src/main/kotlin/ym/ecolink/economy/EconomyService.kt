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

    private val cache = ConcurrentHashMap<UUID, CachedAccount>()
    private val nameIndex = ConcurrentHashMap<String, UUID>()

    @Volatile
    private var mutationListener: ((BalanceSyncRecord) -> Unit)? = null

    fun setMutationListener(listener: (BalanceSyncRecord) -> Unit) {
        mutationListener = listener
    }

    fun ensureAccount(identity: AccountIdentity): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val cached = cache[identity.uuid]
            if (cached != null && cached.isFresh()) {
                return@supplyAsync cached.record
            }
            remember(
                repository.getOrCreate(
                    identity = identity,
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId
                )
            )
        }
    }

    fun getBalance(identity: AccountIdentity): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val cached = cache[identity.uuid]
            if (cached != null && cached.isFresh() && cached.record.username.equals(identity.username, ignoreCase = true)) {
                return@supplyAsync cached.record
            }
            remember(
                repository.getOrCreate(
                    identity = identity,
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId
                )
            )
        }
    }

    fun resolveIdentity(selector: String): CompletableFuture<AccountIdentity?> {
        return dispatcher.supplyAsync {
            parseUuid(selector)?.let { uuid ->
                val cached = cache[uuid]
                if (cached != null && cached.isFresh()) {
                    return@supplyAsync cached.record.toIdentity()
                }
                return@supplyAsync repository.findByUuid(uuid)?.let(::remember)?.toIdentity()
            }

            val cachedUuid = nameIndex[selector.lowercase()]
            if (cachedUuid != null) {
                val cached = cache[cachedUuid]
                if (cached != null && cached.isFresh()) {
                    return@supplyAsync cached.record.toIdentity()
                }
            }

            repository.findByUsername(selector)?.let(::remember)?.toIdentity()
        }
    }

    fun addBalance(
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val updated = remember(
                repository.adjustBalance(
                    identity = target,
                    delta = settings.normalize(amount),
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason,
                    action = LedgerAction.ADD,
                    requireSufficient = false
                )
            )
            publish(updated)
            updated
        }
    }

    fun takeBalance(
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val updated = remember(
                repository.adjustBalance(
                    identity = target,
                    delta = settings.normalize(amount).negate(),
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason,
                    action = LedgerAction.TAKE,
                    requireSufficient = true
                )
            )
            publish(updated)
            updated
        }
    }

    fun setBalance(
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            val updated = remember(
                repository.setBalance(
                    identity = target,
                    amount = settings.normalize(amount),
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason
                )
            )
            publish(updated)
            updated
        }
    }

    fun transfer(
        source: AccountIdentity,
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<TransferReceipt> {
        return dispatcher.supplyAsync {
            if (source.uuid == target.uuid) {
                throw IllegalArgumentException("Cannot transfer to self.")
            }
            val receipt = repository.transfer(
                source = source,
                target = target,
                amount = settings.normalize(amount),
                startingBalance = settings.startingBalance,
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

    fun getTopBalances(page: Int): CompletableFuture<List<AccountRecord>> {
        return dispatcher.supplyAsync {
            val safePage = page.coerceAtLeast(1)
            val limit = settings.feature.topPageSize
            val offset = (safePage - 1) * limit
            repository.findTopAccounts(limit, offset).map(::remember)
        }
    }

    fun getLedger(identity: AccountIdentity, page: Int): CompletableFuture<List<LedgerEntry>> {
        return dispatcher.supplyAsync {
            val safePage = page.coerceAtLeast(1)
            val limit = settings.feature.ledgerPageSize
            val offset = (safePage - 1) * limit
            repository.findLedgerEntries(identity.uuid, limit, offset)
        }
    }

    fun peekCached(uuid: UUID): AccountRecord? {
        val cached = cache[uuid]
        return if (cached != null && cached.isFresh()) cached.record else null
    }

    fun peekCached(selector: String): AccountRecord? {
        val uuid = nameIndex[selector.lowercase()] ?: return null
        return peekCached(uuid)
    }

    fun rememberRemote(record: BalanceSyncRecord) {
        val incoming = AccountRecord(
            uuid = record.uuid,
            username = record.username,
            balance = settings.normalize(record.balance),
            version = record.version,
            updatedAt = record.updatedAt
        )
        val current = cache[record.uuid]
        if (current == null || current.record.version <= incoming.version || !current.isFresh()) {
            remember(incoming)
        }
    }

    fun awaitAccount(identity: AccountIdentity): AccountRecord {
        return getBalance(identity).get(settings.compatibility.vault.syncTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun awaitIdentity(selector: String): AccountIdentity? {
        return resolveIdentity(selector).get(settings.compatibility.vault.syncTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun remember(record: AccountRecord): AccountRecord {
        cache[record.uuid] = CachedAccount(record, System.currentTimeMillis() + settings.cacheTtlMillis)
        nameIndex[record.username.lowercase()] = record.uuid
        return record
    }

    private fun publish(record: AccountRecord) {
        mutationListener?.invoke(
            BalanceSyncRecord(
                serverId = settings.serverId,
                uuid = record.uuid,
                username = record.username,
                balance = record.balance,
                version = record.version,
                updatedAt = record.updatedAt
            )
        )
    }

    private fun parseUuid(raw: String): UUID? {
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private data class CachedAccount(
        val record: AccountRecord,
        val expiresAt: Long
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() <= expiresAt
    }
}
