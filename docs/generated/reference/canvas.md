---
title: "canvas"
description: "Canvases: builder flows that create their objects only when applied."
order: 6
---

Canvases: builder flows that create their objects only when applied.

Every command also takes the global flags `--json`, `--profile <name>`.

### canvas create

Create a canvas, from a definition file or empty.

```sh
lingara canvas create [flags]
```

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | no |  |  | The canvas's name |
| `--file <definition.json>` | path | no |  |  | The definition; absent, an empty one |

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

### canvas list

List your canvases.

```sh
lingara canvas list [flags]
```

Table columns: `ID`, `NAME`, `DRAFT`, `ACTIVE`, `UPDATED`.

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

### canvas get

Show one canvas.

```sh
lingara canvas get <id> [flags]
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

### canvas update

Rename a canvas.

```sh
lingara canvas update <id> --name <name> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | yes |  |  | The new name |

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

### canvas draft

Save a definition as the canvas's draft. Creates nothing else.

```sh
lingara canvas draft <id> --file <definition.json> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--file <definition.json>` | path | yes |  |  | The definition |

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

### canvas apply

Create the canvas's objects by running this CLI's own commands, then activate it.

```sh
lingara canvas apply <id> [flags]
```

At most one of `--dry-run`, `--print-commands` may be given.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--dry-run` | bool | no |  |  | Print what each object would do, and write nothing |
| `--print-commands` | bool | no |  |  | Print the commands apply would run, and write nothing |

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
| 15 | `render_url_ephemeral` |
| 130 | `interrupted` |

See exactly what apply would run:

```sh
lingara canvas apply cv_123 --print-commands
```

### canvas delete

Delete a canvas, or one version. The objects it made stay.

```sh
lingara canvas delete <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara canvas delete <id> -y --confirm <email>`.

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

