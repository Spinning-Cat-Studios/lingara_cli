---
title: "embed"
description: "Lingara inside your game or website: an embed and the metered OAuth client behind it."
order: 3
---

Lingara inside your game or website: an embed and the metered OAuth client behind it.

Every command also takes the global flags `--json`, `--profile <name>`.

### embed create

Create an embed and its metered OAuth client, and show the client's secret once.

```sh
lingara embed create --name <name> [flags]
```

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | yes |  |  | The embed's name |
| `--origin <origin>` | string, repeatable | no |  |  | A web origin allowed to use it (up to 10) |
| `--scope <scope>` | string, repeatable | no |  |  | A scope beside embed:mint |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 5 | `refused` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 130 | `interrupted` |

### embed list

List your embeds.

```sh
lingara embed list [flags]
```

Table columns: `ID`, `NAME`, `CLIENT`, `ORIGINS`, `SERVING`.

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 5 | `refused` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 130 | `interrupted` |

### embed get

Show one embed.

```sh
lingara embed get <id> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 4 | `not_found` |
| 5 | `refused` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 130 | `interrupted` |

### embed update

Rename an embed, or replace its origins.

```sh
lingara embed update <id> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | no |  |  | The new name |
| `--origin <origin>` | string, repeatable | no |  |  | Replaces the allowed origins |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 4 | `not_found` |
| 5 | `refused` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 130 | `interrupted` |

### embed delete

Delete an embed and its OAuth client, or one version.

```sh
lingara embed delete <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara embed delete <id> -y --confirm <email>`.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--version <semver>` | string | no |  |  | Delete only this version |
| `-y, --yes` | bool | no |  |  | Answer the y/n question; needs --confirm too |
| `--confirm <email>` | string | no |  |  | Your developer email, typed back; needs -y too |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 4 | `not_found` |
| 5 | `refused` |
| 6 | `not_confirmed` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 130 | `interrupted` |

