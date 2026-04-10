# Ecolink

Ecolink is a cross-server economy plugin for `Paper`, `Spigot`, and `Folia`.

Current focus:
- async database-backed economy core
- PostgreSQL as the recommended primary backend
- MySQL compatibility when you already run it
- multi-currency balances with per-currency scale and transfer rules
- idempotent recharge pipeline for webhooks, stores, and manual compensation
- MiniMessage-based localization with configurable zh/en language packs
- CMI and EssentialsX balance migration
- optional Vault, PlaceholderAPI, and Redis balance sync bridges

## Highlights

- All database reads, writes, migrations, and balance sync work run off the main thread.
- Main-thread interaction is limited to safe Bukkit or Folia boundaries such as sending messages and registering hooks.
- Account identity is stored separately from currency balances, so one player can hold multiple currencies cleanly.
- Legacy single-balance tables are migrated into the default currency automatically on startup.
- Recharge requests are deduplicated by `transactionId`, so repeated callbacks only apply once.
- Runtime messages are loaded into memory at startup, so command responses do not touch disk IO.
- This plugin does not mutate inventories or item stacks, so the current feature set does not introduce item loss or duplication paths.

## Database Recommendation

Default recommendation: `PostgreSQL`

Why:
- clearer transactional semantics for cross-server balance updates
- better fit for future audit trails, recharge history, and management tooling
- safer path when you expand into larger server clusters

`MySQL` is still supported if that is what your stack already uses.

## Commands

- `/balance [player] [currency]`
- `/pay <player> <amount> [currency]`
- `/baltop [currency] [page]`
- `/ecolink ledger [player] [currency] [page]`
- `/ecolink currencies`
- `/ecolink set <player> <amount> [currency]`
- `/ecolink add <player> <amount> [currency]`
- `/ecolink take <player> <amount> [currency]`
- `/ecolink recharge <player> <currency> <amount> <transactionId> [reason...]`
- `/ecolink migrate <cmi|essentials|all> [overwrite]`

## Permissions

- `ecolink.pay`
- `ecolink.balance.others`
- `ecolink.baltop`
- `ecolink.ledger`
- `ecolink.ledger.others`
- `ecolink.admin`
- `ecolink.recharge`
- `ecolink.migrate`

## Multi-Currency Model

Currencies are configured in `src/main/resources/config.yml` under `currencies.list`.

Per currency you can define:
- `display-name`
- `symbol`
- `scale`
- `starting-balance`
- `transferable`
- `vault-primary`

Compatibility notes:
- old commands still default to `currencies.default-key`
- Vault exposes the currency marked with `vault-primary`, or the default currency if none is marked
- PlaceholderAPI keeps `%ecolink_balance%` and `%ecolink_balance_formatted%`, and now also supports `%ecolink_balance_<currency>%` and `%ecolink_balance_formatted_<currency>%`

## Localization

Language settings live in `src/main/resources/config.yml`:

- `language.locale`
- `language.fallback-locale`
- `language.directory`

Bundled language files:

- `lang/zh_CN.yml`
- `lang/en_US.yml`

Design notes:

- all runtime prompts are resolved from language files
- `prefix` is a dedicated top-level key
- other messages reuse it through the `<prefix>` placeholder
- rendering uses `MiniMessage`, so RGB hex colors are supported directly
- dynamic values are injected as unparsed placeholders to avoid placeholder injection problems

## Idempotent Recharge

Use `recharge` when money comes from an external source such as:
- web store callbacks
- payment webhooks
- admin compensation scripts
- future web control panel actions

Example:

```text
/ecolink recharge Notch coins 100.00 order-2026-04-10-0001 Tebex
```

Behavior:
- first call applies the recharge and records the transaction id
- repeated calls with the same transaction id return the original result without applying the balance again
- if the same transaction id is reused for a different player, currency, or amount, the call fails

## Migration

The built-in migration pipeline imports into the default currency.

Supported sources:
- `CMI sqlite`
- `CMI mysql`
- `EssentialsX userdata/*.yml`

Commands:

```text
/ecolink migrate cmi
/ecolink migrate essentials overwrite
/ecolink migrate all
```

## Optional Bridges

Vault:
- Ecolink registers a primary economy bridge for the configured Vault currency.
- Vault itself is synchronous, so the bridge uses a bounded timeout and delegates to the async core.

PlaceholderAPI:
- `%ecolink_balance%`
- `%ecolink_balance_formatted%`
- `%ecolink_balance_coins%`
- `%ecolink_balance_formatted_gems%`
- `%ecolink_server%`

Redis:
- publishes cross-server balance snapshots after mutations
- payloads now include `currencyKey`
- legacy single-currency payloads are still accepted and mapped to the default currency

## Build

Requirements:
- `JDK 21`

Build:

```bash
gradle build
```

Artifact:

```text
build/libs/Ecolink-0.1.0-SNAPSHOT.jar
```

## Next Logical Step

The current architecture is ready for:
- a web management panel
- authenticated recharge webhooks
- multi-currency leaderboards and ledger filters in external tools
- settlement, billing, and anti-fraud workflows
