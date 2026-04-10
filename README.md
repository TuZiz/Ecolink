# Ecolink

Ecolink 是一个面向 `Paper`、`Spigot`、`Folia` 的跨服经济插件。

当前核心方向：
- 异步数据库经济核心
- `PostgreSQL` 作为默认推荐主库
- 兼容 `MySQL`
- 多货币模型，支持运行时启用与删除
- 配置化商品购买系统
- 幂等充值链路
- 基于 MiniMessage 的中英文语言系统
- CMI 与 EssentialsX 经济数据迁移
- 可选接入 Vault、PlaceholderAPI、Redis 跨服同步

## 主要特性

- 所有数据库读写、迁移、删除货币数据、余额同步都在主线程外执行。
- 主线程只处理 Bukkit / Folia 必须的边界操作，例如发消息、注册桥接、执行配置化奖励命令。
- 货币定义和已启用货币分离，`/el create` 与 `/el delete` 会持久化到配置文件并在重载后生效。
- 运行时重载会重新构建插件服务，而不是在旧运行时上做危险热改。
- 商品购买复用同一套异步经济服务，避免业务逻辑分叉。
- 语言文件启动后加载进内存，运行期消息不走磁盘 IO。

## 数据库建议

默认推荐：`PostgreSQL`

原因：
- 跨服余额更新的事务语义更清晰
- 更适合后续账单、审计、风控、管理面板扩展
- 集群规模起来后更稳

如果你当前技术栈已经是 `MySQL`，也可以直接使用。

## 指令设计

### 多货币指令

`el` 只处理多货币逻辑：

- `/el me`
- `/el buy <商品>`
- `/el top <货币> [页码]`
- `/el pay <玩家> <货币> <数量>`
- `/el create <货币>`
- `/el give <玩家> <货币> <数量>`
- `/el reset <玩家> <货币>`
- `/el delete <货币>`
- `/el reload`

### 单币种 / Vault 指令

`money` 只处理 `Vault` 主货币：

- `/money`
- `/money <玩家>`
- `/money pay <玩家> <数量>`
- `/money top [页码]`

### 兼容指令

下面这些兼容命令现在也都是单币种主货币语义：

- `/balance [玩家]`
- `/pay <玩家> <数量>`
- `/baltop [页码]`

下面这些仍然保留在 `el` 下：

- `/el ledger [玩家] [货币] [页码]`
- `/el recharge <玩家> <货币> <数量> <事务号> [原因...]`
- `/el migrate <cmi|essentials|all> [overwrite]`

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

`/el me` 和 `/el buy` 默认不需要额外权限节点。

## 多货币运行模型

货币定义在 `config.yml` 的 `currencies.list` 中。

当前启用的货币在 `currencies.enabled` 中。

也就是说：

- `currencies.list` 决定“系统里可以有哪些货币”
- `currencies.enabled` 决定“当前真正启用了哪些货币”
- `/el create <货币>` 会启用一个已在配置里定义过的货币
- `/el delete <货币>` 会禁用该货币，并清掉它的余额、流水、充值记录

每个货币可配置：

- `display-name`
- `symbol`
- `scale`
- `starting-balance`
- `transferable`
- `vault-primary`

运行时货币管理由这些配置控制：

- `currencies.management.command-enabled`
- `currencies.management.allow-create`
- `currencies.management.allow-delete`
- `currencies.management.protected`

## 商品系统

商品配置位于 `config.yml` 的 `shop.products`。

每个商品支持：

- `display-name`
- `currency`
- `price`
- `commands`
- `enabled`

商品命令中可用占位符：

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

语言配置在 `config.yml`：

- `language.locale`
- `language.fallback-locale`
- `language.directory`

内置语言文件：

- `lang/zh_CN.yml`
- `lang/en_US.yml`

设计约定：

- 所有运行时提示都来自语言文件
- `prefix` 是独立顶层字段
- 其他消息统一通过 `<prefix>` 复用
- 渲染使用 `MiniMessage`，支持 RGB 十六进制颜色

## 幂等充值

当经济金额来自外部系统时，使用 `recharge`：

- 商城回调
- 支付 Webhook
- 管理员补单
- 后续 Web 管理面板入账

示例：

```text
/el recharge Notch coins 100.00 order-2026-04-10-0001 Tebex
```

行为：

- 第一次调用会真正入账，并记录事务号
- 同一事务号重复调用，只返回原结果，不重复加钱
- 同一事务号如果换了玩家、货币或金额，会直接报错

## 数据迁移

内置迁移默认导入到默认货币。

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

## 可选桥接

### Vault

- Ecolink 会把 `vault-primary` 对应的货币注册为主经济。
- Vault 本身是同步接口，所以桥接层使用受限超时调用异步核心。

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

构建产物：

```text
build/libs/Ecolink-0.1.0-SNAPSHOT.jar
```
