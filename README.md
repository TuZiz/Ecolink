# Ecolink

Ecolink 是一个面向 `Paper`、`Spigot`、`Folia` 的跨服经济插件。

核心目标：

- 数据库存储优先，默认推荐 `PostgreSQL`
- 兼容 `MySQL`
- 经济事务、迁移、缓存刷新走异步线程池
- 支持从 `CMI` 与 `EssentialsX` 一键迁移余额数据
- 通过共享数据库实现多服共用同一套经济账户
- 可选接入 `Vault`
- 可选接入 `PlaceholderAPI`
- 可选接入 `Redis` 做跨服实时余额同步

## 当前特性

- 异步插件启动与数据库初始化
- `PostgreSQL` / `MySQL` 双后端
- 基于 JDBC 事务的余额原子更新
- 本地短 TTL 账户缓存
- `Paper` / `Spigot` / `Folia` 安全调度桥
- `CMI sqlite` / `CMI mysql` 余额导入
- `EssentialsX userdata/*.yml` 余额导入
- 账变流水表
- `Vault` 经济桥
- `PlaceholderAPI` 占位符
- `/baltop` 排行榜
- `/ecolink ledger` 流水查询
- `Redis pub/sub` 余额实时同步

## 数据库建议

默认推荐 `PostgreSQL`。

原因：

- 跨服并发更新更稳
- 事务和锁语义更清晰
- 后续扩展账单、审计、排行榜、订阅通知更顺手

如果你已经有成熟的 MySQL 集群，也可以直接切到 `MySQL`。

## 命令

- `/balance`
- `/balance <player>`
- `/pay <player> <amount>`
- `/baltop [page]`
- `/ecolink set <player> <amount>`
- `/ecolink add <player> <amount>`
- `/ecolink take <player> <amount>`
- `/ecolink ledger [player] [page]`
- `/ecolink migrate <cmi|essentials|all> [overwrite]`

## 权限

- `ecolink.pay`
- `ecolink.balance.others`
- `ecolink.baltop`
- `ecolink.ledger`
- `ecolink.ledger.others`
- `ecolink.admin`
- `ecolink.migrate`

## 配置

主配置文件：`src/main/resources/config.yml`

关键项：

- `server.id`
- `async.worker-threads`
- `economy.currency-symbol`
- `economy.starting-balance`
- `feature.top-page-size`
- `feature.ledger-page-size`
- `compatibility.vault.enabled`
- `compatibility.placeholderapi.enabled`
- `sync.redis.enabled`
- `sync.redis.host`
- `sync.redis.port`
- `sync.redis.channel`
- `storage.type`
- `storage.host`
- `storage.port`
- `storage.database`
- `storage.username`
- `storage.password`
- `storage.table-prefix`
- `migration.cmi-directory`
- `migration.essentials-directory`

默认迁移目录是：

- `plugins/CMI`
- `plugins/Essentials`

## 构建

要求：

- `JDK 21`

构建命令：

```bash
gradle build
```

产物位于：

```text
build/libs/Ecolink-0.1.0-SNAPSHOT.jar
```

## 迁移说明

### CMI

支持两种源：

- `cmi.sqlite.db`
- `Settings/DataBaseInfo.yml` 指向的 MySQL

读取字段：

- `player_uuid`
- `username`
- `Balance`
- `Economy`

### EssentialsX

默认从 `userdata/*.yml` 读取：

- 文件名 UUID
- `last-account-name`
- `money`

## 线程模型

Ecolink 不会把数据库读写放在主线程。

实际策略是：

- IO、事务、迁移、查询走插件异步线程池
- 玩家消息和平台要求的 Bukkit/Folia 交互走安全调度桥

这比“所有代码都硬塞进异步线程”更符合 `Paper` / `Spigot` / `Folia` 的运行约束。

需要说明的一点：

- `Vault` 本身是同步 API，所以 `Vault` 兼容层会走一个受限超时的兼容桥
- 核心经济事务和数据库读写仍然保持异步实现

## 下一步建议

这一版已经把现代化基础设施铺好了，下一步我更建议往这几个方向继续扩：

- Web 管理面板：账户检索、流水查询、冻结、手动修账
- 幂等事务 ID：为商城充值、Webhook 入账、后台补单做去重
- 多货币模型：金币、点券、绑定币分层
- 排行榜缓存表：大服场景下进一步减轻主账户表排序压力
- 审计导出和风控规则：大额转账告警、黑名单、批量回滚
- Prometheus 指标：观察 DB 延迟、命令耗时、Redis 同步健康度

## 当前状态

当前版本已经完成：

- 经济核心
- 跨服数据库层
- CMI / EssentialsX 迁移
- Paper / Spigot / Folia 兼容调度
- Vault 兼容层
- PlaceholderAPI 占位符
- baltop 和流水查询
- Redis 实时同步

如果你要，我下一步可以继续把 `Vault + 排行榜 + PlaceholderAPI + Webhook 入账` 这一组一起补掉。
