package ym.ecolink.economy

import ym.ecolink.config.PluginSettings
import ym.ecolink.platform.ServerTaskDispatcher
import ym.ecolink.storage.EconomyRepository
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class EconomyService(
    private val settings: PluginSettings,
    private val repository: EconomyRepository,
    private val dispatcher: ServerTaskDispatcher
) {

    private val cache = ConcurrentHashMap<UUID, CachedAccount>()
    private val nameIndex = ConcurrentHashMap<String, UUID>()

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
            remember(
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
        }
    }

    fun takeBalance(
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            remember(
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
        }
    }

    fun setBalance(
        target: AccountIdentity,
        amount: BigDecimal,
        actor: String,
        reason: String
    ): CompletableFuture<AccountRecord> {
        return dispatcher.supplyAsync {
            remember(
                repository.setBalance(
                    identity = target,
                    amount = settings.normalize(amount),
                    startingBalance = settings.startingBalance,
                    serverId = settings.serverId,
                    actor = actor,
                    reason = reason
                )
            )
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
            remember(receipt.from)
            remember(receipt.to)
            receipt
        }
    }

    private fun remember(record: AccountRecord): AccountRecord {
        cache[record.uuid] = CachedAccount(record, System.currentTimeMillis() + settings.cacheTtlMillis)
        nameIndex[record.username.lowercase()] = record.uuid
        return record
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
