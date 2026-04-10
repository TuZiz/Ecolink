# Ecolink

Ecolink 是一个面向 `Paper`、`Spigot`、`Folia` 的跨服经济插件。

核心目标：

- 数据库存储优先，默认推荐 `PostgreSQL`
- 兼容 `MySQL`
- 经济事务、迁移、缓存刷新走异步线程池
- 支持从 `CMI` 与 `EssentialsX` 一键迁移余额数据
- 通过共享数据库实现多服共用同一套经济账户

## 当前特性

- 异步插件启动与数据库初始化
- `PostgreSQL` / `MySQL` 双后端
- 基于 JDBC 事务的余额原子更新
- 本地短 TTL 账户缓存
- `Paper` / `Spigot` / `Folia` 安全调度桥
- `CMI sqlite` / `CMI mysql` 余额导入
- `EssentialsX userdata/*.yml` 余额导入
- 账变流水表

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
- `/ecolink set <player> <amount>`
- `/ecolink add <player> <amount>`
- `/ecolink take <player> <amount>`
- `/ecolink migrate <cmi|essentials|all> [overwrite]`

## 权限

- `ecolink.pay`
- `ecolink.balance.others`
- `ecolink.admin`
- `ecolink.migrate`

## 配置

主配置文件：`src/main/resources/config.yml`

关键项：

- `server.id`
- `async.worker-threads`
- `economy.currency-symbol`
- `economy.starting-balance`
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

## 现代化迭代建议

如果继续做下一版，我建议优先上这几项：

- `Vault` 适配层：让商店、任务、拍卖、菜单插件直接接入
- Web 管理面板：账户检索、流水查询、冻结、手动修账
- Redis 或数据库通知总线：把跨服余额变更从短 TTL 缓存升级成准实时推送
- 排行榜与 Top Balance 缓存表：避免每次直接扫主账户表
- 幂等事务 ID：为后台补偿、商城充值、Webhook 入账做去重保护
- 双货币或多货币模型：金币、点券、绑定币拆分
- 玩家账单查询命令：支持按时间、来源、类型筛选流水
- Prometheus 指标：观察 DB 延迟、命令耗时、迁移成功率
- PlaceholderAPI 支持：让菜单、全息、记分板直接显示余额
- 审计日志导出：方便查黑钱、查异常转账、回滚事故

## 当前状态

当前版本已经完成：

- 经济核心
- 跨服数据库层
- CMI / EssentialsX 迁移
- Paper / Spigot / Folia 兼容调度

如果你要，我下一步可以继续把 `Vault + 排行榜 + PlaceholderAPI + Webhook 入账` 这一组一起补掉。
