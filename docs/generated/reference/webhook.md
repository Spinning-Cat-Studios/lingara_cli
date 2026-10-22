---
title: "webhook"
description: "Webhooks: events your game sends in, and events Lingara sends out."
order: 5
---

Webhooks: events your game sends in, and events Lingara sends out.

Every command also takes the global flags `--json`, `--profile <name>`.

### webhook inbound create

Create an inbound webhook and its client, and show the client's secret once.

```sh
lingara webhook inbound create --name <name> [flags]
```

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | yes |  |  | The webhook's name |
| `--event <type>` | string, repeatable | no |  |  | An inbound event type it accepts; absent, every one |

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

Accept one event type:

```sh
lingara webhook inbound create --name 'Game server' --event world.practice_completed
```

### webhook inbound list

List your inbound webhooks.

```sh
lingara webhook inbound list [flags]
```

Table columns: `ID`, `NAME`, `CLIENT`, `EVENTS`.

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

### webhook inbound get

Show one inbound webhook.

```sh
lingara webhook inbound get <id> [flags]
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

### webhook inbound update

Rename an inbound webhook, or replace its event types.

```sh
lingara webhook inbound update <id> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | no |  |  | The new name |
| `--event <type>` | string, repeatable | no |  |  | Replaces the event types |

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

### webhook inbound delete

Delete an inbound webhook, or one version. Its client stays.

```sh
lingara webhook inbound delete <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara webhook inbound delete <id> -y --confirm <email>`.

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

### webhook outbound create

Create an outbound webhook, and show its signing secret once.

```sh
lingara webhook outbound create --client <id> --url <url> --event <type> [flags]
```

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--client <id>` | string | yes |  |  | The OAuth client it belongs to |
| `--url <url>` | string | yes |  |  | The endpoint deliveries go to |
| `--event <type>` | string, repeatable | yes |  |  | An event type to deliver |

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

### webhook outbound list

List your outbound webhooks.

```sh
lingara webhook outbound list [flags]
```

Table columns: `ID`, `CLIENT`, `URL`, `EVENTS`, `ENABLED`.

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

### webhook outbound get

Show one outbound webhook.

```sh
lingara webhook outbound get <id> [flags]
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

### webhook outbound update

Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.

```sh
lingara webhook outbound update <id> [flags]
```

At most one of `--enable`, `--disable` may be given.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--url <url>` | string | no |  |  | The new endpoint |
| `--event <type>` | string, repeatable | no |  |  | Replaces the event types |
| `--enable` | bool | no |  |  | Resume deliveries |
| `--disable` | bool | no |  |  | Pause deliveries |

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

### webhook outbound replace

Start again: a new endpoint with a new signing secret, then the old one deleted.

```sh
lingara webhook outbound replace <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara webhook outbound replace <id> -y --confirm <email>`.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--url <url>` | string | no |  |  | The new endpoint; absent, the old one's |
| `--event <type>` | string, repeatable | no |  |  | The new event types; absent, the old one's |
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

### webhook outbound test

Send a test delivery.

```sh
lingara webhook outbound test <id> [flags]
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

### webhook outbound delete

Delete an outbound webhook, or one version.

```sh
lingara webhook outbound delete <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara webhook outbound delete <id> -y --confirm <email>`.

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

