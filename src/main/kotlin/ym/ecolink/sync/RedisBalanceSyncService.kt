package ym.ecolink.sync

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import ym.ecolink.config.RedisSyncSettings
import ym.ecolink.economy.BalanceSyncRecord
import ym.ecolink.economy.EconomyService
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class RedisBalanceSyncService(
    private val localServerId: String,
    private val settings: RedisSyncSettings,
    private val economyService: EconomyService,
    private val logger: Logger
) : AutoCloseable {

    private val pool: JedisPool = JedisPool(
        JedisPoolConfig(),
        settings.host,
        settings.port,
        settings.timeoutMillis.toInt(),
        settings.password.ifBlank { null },
        settings.database
    )

    @Volatile
    private var subscriber: JedisPubSub? = null

    @Volatile
    private var subscriberThread: Thread? = null

    fun start() {
        if (!settings.enabled) {
            return
        }
        val pubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                if (channel != settings.channel) {
                    return
                }
                runCatching { decode(message) }
                    .onSuccess { record ->
                        if (record.serverId != localServerId) {
                            economyService.rememberRemote(record)
                        }
                    }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to parse Redis balance sync message.", error)
                    }
            }
        }
        subscriber = pubSub
        subscriberThread = Thread({
            runCatching {
                pool.resource.use { jedis ->
                    jedis.subscribe(pubSub, settings.channel)
                }
            }.onFailure { error ->
                if (!pool.isClosed) {
                    logger.log(Level.WARNING, "Redis balance subscriber stopped unexpectedly.", error)
                }
            }
        }, "ecolink-redis-sub").apply {
            isDaemon = true
            start()
        }
    }

    fun publish(record: BalanceSyncRecord) {
        if (!settings.enabled) {
            return
        }
        runCatching {
            pool.resource.use { jedis ->
                jedis.publish(settings.channel, encode(record))
            }
        }.onFailure { error ->
            logger.log(Level.WARNING, "Failed to publish Redis balance sync message.", error)
        }
    }

    override fun close() {
        subscriber?.unsubscribe()
        subscriberThread?.interrupt()
        pool.close()
    }

    private fun encode(record: BalanceSyncRecord): String {
        val encodedName = Base64.getUrlEncoder().encodeToString(record.username.toByteArray(Charsets.UTF_8))
        return listOf(
            record.serverId,
            record.uuid.toString(),
            encodedName,
            record.balance.toPlainString(),
            record.version.toString(),
            record.updatedAt.toEpochMilli().toString()
        ).joinToString("|")
    }

    private fun decode(payload: String): BalanceSyncRecord {
        val parts = payload.split('|')
        require(parts.size == 6) { "Invalid sync payload" }
        val username = String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        return BalanceSyncRecord(
            serverId = parts[0],
            uuid = UUID.fromString(parts[1]),
            username = username,
            balance = BigDecimal(parts[3]),
            version = parts[4].toLong(),
            updatedAt = Instant.ofEpochMilli(parts[5].toLong())
        )
    }
}
