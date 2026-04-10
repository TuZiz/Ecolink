# Ecolink

Ecolink 是一个面向 `Paper`、`Spigot`、`Folia` 的异步经济插件。

当前定位：

- 默认开箱即用本地 `SQLite`
- 生产环境优先推荐 `PostgreSQL`
- 兼容 `MySQL`
- 多货币模型
- `money` 单币种 / `el` 多货币分层指令
- 幂等充值
- MiniMessage 中英文语言系统
- 支持从 `CMI`、`EssentialsX` 导入经济数据
- 支持把当前库官方迁移到远端 `PostgreSQL` / `MySQL`
- 可选接入 `Vault`、`PlaceholderAPI`、`Redis`

## 核心特性

- 数据库读写、迁移、排行榜、流水查询都走异步线程池，不在主线程阻塞。
- 主线程只保留 Bukkit / Folia 必须的边界操作，例如消息发送、命令注册、桥接挂载。
- `money` 只处理 `Vault` 主货币，`el` 保留多货币管理和多货币业务。
- 货币定义与启用集分离，支持 `/el create`、`/el delete`、`/el reload`。
- 货币级能力可配置：是否允许转账、是否对玩家可见、是否允许上排行榜、是否作为 Vault 主货币。
- 商品购买复用同一套经济服务，不额外分叉资金逻辑。
- 幂等充值支持事务号去重，适合商城、Webhook、补单。
- 语言文件预加载进内存，运行时消息不走磁盘 IO。

## 数据库建议

默认开箱即用：`SQLite`

推荐生产环境：`PostgreSQL`

原因：

- 跨服余额事务语义更稳
- 更适合后续账单、审计、风控、Web 面板扩展
- 多服规模起来后维护成本更低

如果你的现有基础设施已经是 `MySQL`，也可以直接使用。

建议路线：

- 单服试运行：保持默认 `SQLite`
- 多服 / 正式环境：切到 `PostgreSQL`
- 已经开服后再扩服：先配远端库，再执行 `/el migrate storage`

## 指令设计

### 多货币指令

`el` 只处理多货币逻辑：

- `/el me`
- `/el buy <商品>`
- `/el top <货币> [页码]`
- `/el pay <玩家> <货币> <数量>`
- `/el create <货币>`
- `/el give <玩家> <货币> <数量>`
- `/el set <玩家> <数量> [货币]`
- `/el add <玩家> <数量> [货币]`
- `/el take <玩家> <数量> [货币]`
- `/el reset <玩家> <货币>`
- `/el delete <货币>`
- `/el ledger [玩家] [货币] [页码]`
- `/el recharge <玩家> <货币> <数量> <事务号> [来源标签...]`
- `/el migrate <cmi|essentials|all|storage> [overwrite]`
- `/el currencies`
- `/el reload`

### 单币种 / Vault 指令

`money` 只处理 `vault-primary: true` 的主货币：

- `/money`
- `/money <玩家>`
- `/money pay <玩家> <数量>`
- `/money top [页码]`
- `/money give <玩家> <数量>`
- `/money set <玩家> <数量>`
- `/money take <玩家> <数量>`

### 兼容指令

下面这些兼容命令也都已经收敛到主货币语义：

- `/balance [玩家]`
- `/pay <玩家> <数量>`
- `/baltop [页码]`

## 权限

默认公开：

- `ecolink.pay`

默认管理员：

- `ecolink.balance.others`
- `ecolink.ledger`
- `ecolink.ledger.others`
- `ecolink.recharge`
- `ecolink.migrate`
- `ecolink.admin`

说明：

- `/el me` 和 `/el buy` 默认不需要额外权限
- `money give/set/take` 默认复用 `ecolink.admin`

## 多货币模型

货币定义位于 `config.yml` 的 `currencies.list`。

当前启用货币位于 `currencies.enabled`。

这两层的区别：

- `currencies.list` 决定系统里允许定义哪些货币
- `currencies.enabled` 决定当前运行时真正启用了哪些货币

每个货币支持这些配置：

- `display-name`
- `symbol`
- `scale`
- `starting-balance`
- `transferable`
- `player-visible`
- `leaderboard-enabled`
- `vault-primary`

字段含义：

- `transferable: false`
  该货币不能被玩家通过 `/el pay` 互转，适合签到币、绑定币
- `player-visible: false`
  该货币不会出现在 `/el me` 视图里
- `leaderboard-enabled: false`
  该货币不能使用 `/el top` 或 `baltop` 类排行视图
- `vault-primary: true`
  该货币会成为 `money` 与 `Vault` 对外暴露的主货币

示例：

```yaml
currencies:
  default-key: "coins"
  enabled:
    - "coins"
    - "checkin"
  list:
    coins:
      display-name: "金币"
      symbol: "$"
      scale: 2
      starting-balance: "0.00"
      transferable: true
      player-visible: true
      leaderboard-enabled: true
      vault-primary: true
    checkin:
      display-name: "签到币"
      symbol: "签"
      scale: 0
      starting-balance: "0"
      transferable: false
      player-visible: false
      leaderboard-enabled: false
      vault-primary: false
```

## 来源标签与幂等充值

Ecolink 内部已经收口了一套标准来源标签，便于流水、审计和后续风控使用。

内置标签示例：

- `admin.give`
- `admin.set`
- `admin.add`
- `admin.take`
- `admin.reset`
- `player.pay`
- `shop.buy.<product>`
- `recharge.manual`
- `vault.deposit`
- `vault.withdraw`
- `migration.cmi`
- `migration.essentials`

`/el recharge` 用法：

```text
/el recharge Notch coins 100.00 order-2026-04-10-0001 shop.tier1
```

行为说明：

- 第一次调用会真实入账
- 同一事务号重复调用只返回原结果，不会重复加钱
- 同一事务号如果换了玩家、货币或金额，会直接报错

## 商品系统

商品配置位于 `config.yml` 的 `shop.products`。

每个商品支持：

- `display-name`
- `currency`
- `price`
- `commands`
- `enabled`

可用占位符：

- `<player>`
- `<player_uuid>`
- `<product>`
- `<product_display>`
- `<currency>`
- `<currency_display>`
- `<price>`
- `<price_plain>`

示例：

```yaml
shop:
  enabled: true
  products:
    vip_week:
      enabled: true
      display-name: "VIP 7 Days"
      currency: "coins"
      price: "5000.00"
      commands:
        - "lp user <player> parent addtemp vip 7d"
```

## 语言系统

语言配置：

- `language.locale`
- `language.fallback-locale`
- `language.directory`

内置语言文件：

- `lang/zh_CN.yml`
- `lang/en_US.yml`

设计约束：

- 所有运行时提示都在语言文件里
- `prefix` 是独立顶层键
- 其他消息统一通过 `<prefix>` 复用
- 使用 `MiniMessage`
- 支持十六进制 `RGB` 颜色

## 数据迁移

### 外部经济导入

默认导入到当前默认货币。

支持来源：

- `CMI sqlite`
- `CMI mysql`
- `EssentialsX userdata/*.yml`

示例：

```text
/el migrate cmi
/el migrate essentials overwrite
/el migrate all
```

### 当前库迁移到远端库

适用场景：

- 单服先用默认 `SQLite`
- 后期扩服，需要迁移到 `PostgreSQL` / `MySQL`

先在 `config.yml` 配置目标库：

```yaml
migration:
  storage-target:
    enabled: true
    type: "postgresql"
    host: "127.0.0.1"
    port: 5432
    database: "ecolink_cluster"
    schema: "public"
    username: "postgres"
    password: "change-me"
    table-prefix: "ecolink_"
```

然后执行：

```text
/el migrate storage
```

如果目标库里已有数据并且你确认要覆盖：

```text
/el migrate storage overwrite
```

迁移内容包含：

- 账户主表
- 货币余额
- 资金流水
- 幂等充值记录

注意：

- 目标库必须是 `PostgreSQL` 或 `MySQL`
- 源库和目标库不能配置成同一个地址
- 建议先停服或在低峰期执行

## 可选桥接

### Vault

- `vault-primary: true` 的货币会注册为主经济
- Vault 自身是同步接口，所以桥接层使用受限超时去调用异步核心

### PlaceholderAPI

支持：

- `%ecolink_balance%`
- `%ecolink_balance_formatted%`
- `%ecolink_balance_coins%`
- `%ecolink_balance_formatted_gems%`
- `%ecolink_server%`

### Redis

- 余额变更后会发布跨服同步消息
- 同步载荷包含 `currencyKey`

## 构建

环境要求：

- `JDK 21`

构建命令：

```bash
gradle build
```

产物：

```text
build/libs/Ecolink-0.1.0-SNAPSHOT.jar
```
