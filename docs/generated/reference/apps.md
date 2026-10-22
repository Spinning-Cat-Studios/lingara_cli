---
title: "apps"
description: "Apps that render cards inside Lingara."
order: 2
---

Apps that render cards inside Lingara.

Every command also takes the global flags `--json`, `--profile <name>`.

### apps create

Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.

```sh
lingara apps create <name> [flags]
```

At most one of `--manifest`, `--lang` may be given.

At most one of `--manifest`, `--bare` may be given.

At most one of `--manifest`, `--dir` may be given.

At most one of `--manifest`, `--no-register` may be given.

At most one of `--manifest`, `--description` may be given.

At most one of `--manifest`, `--port` may be given.

At most one of `--manifest`, `--slot` may be given.

At most one of `--manifest`, `--context` may be given.

At most one of `--client`, `--lang` may be given.

At most one of `--client`, `--bare` may be given.

At most one of `--client`, `--dir` may be given.

| Argument | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--client <id>` | string | no |  |  | The OAuth client's id (with --manifest) |
| `--manifest <path>` | path | no |  |  | The app's manifest file (with --client) |
| `--lang <app-kit-lang>` | enum: `typescript`, `rust`, `go`, `java`, `kotlin`, `ruby`, `php` | no | `typescript` |  | The app's language |
| `--bare` | bool | no |  |  | The manifest as code in the app's language, and a handler on the kit's primitives |
| `--dir <path>` | path | no |  |  | Where to write the project; default ./<name> |
| `--no-register` | bool | no |  |  | Scaffold only; register later with apps update |
| `--description <text>` | string | no |  |  | One sentence about the app |
| `--port <n>` | int | no |  |  | The port the app listens on; default 8787 |
| `--slot <slot>` | enum: `home.side`, `plans.empty_detail`, repeatable | no |  |  | Where the app's card may appear; default home.side |
| `--context <slice>` | string, repeatable | no |  |  | A context slice the app receives |
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

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
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

Scaffold a TypeScript app and register it, private:

```sh
lingara apps create luba --lang typescript
```

### apps list

List your apps, or the apps platform.toml pins.

```sh
lingara apps list [flags]
```

Table columns: `ID`, `NAME`, `CLIENT`, `SERVING`, `VISIBILITY`.

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--pinned` | bool | no |  |  | List platform.toml's rows instead, reading nothing from the platform |
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 5 | `refused` |
| 7 | `unavailable` |
| 8 | `platform_disabled` |
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

### apps get

Show one app.

```sh
lingara apps get <id> [flags]
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

### apps update

Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file.

```sh
lingara apps update [id] [flags]
```

At most one of `--manifest`, `--version` may be given.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | no |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--manifest <path>` | path | no |  |  | The app's manifest file: makes a new version |
| `--version <semver>` | string | no |  |  | Cut a draft version from the project's manifest |
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

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
| 14 | `connection_scripted` |
| 15 | `render_url_ephemeral` |
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

### apps dev

Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.

```sh
lingara apps dev [name] [flags]
```

At most one of `--reset`, `--port` may be given.

At most one of `--reset`, `--tunnel` may be given.

At most one of `--reset`, `--render-url` may be given.

At most one of `--reset`, `--take-over` may be given.

At most one of `--reset`, `--write-env` may be given.

At most one of `--reset`, `--show-env` may be given.

| Argument | Type | Required | Description |
|---|---|---|---|
| `name` | string | no |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--port <n>` | int | no |  |  | The port the app listens on; default the row's |
| `--tunnel <tunnel>` | enum: `cloudflared`, `none` | no | `cloudflared` |  | The tunnel to open; none needs --render-url |
| `--render-url <https-url>` | string | no |  |  | With --tunnel none, the URL the dev version renders from |
| `--take-over` | bool | no |  |  | End another terminal's session on this app first |
| `--write-env <file>` | path | no |  |  | Also write the session's variables to this gitignored file, mode 0600 |
| `--show-env` | bool | no |  |  | List the names of the variables the app receives |
| `--reset` | bool | no |  |  | Return your install to the serving version, and do nothing else |
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

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
| 9 | `tool_missing` |
| 10 | `tunnel_failed` |
| 11 | `dev_session_held` |
| 12 | `dev_session_lost` |
| 13 | `app_exited` |
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 18 | `app_not_listening` |
| 130 | `interrupted` |

Serve luba from this machine to your own install:

```sh
lingara apps dev luba
```

### apps status

Show where the app's dev version renders from: live, parked, or the serving version.

```sh
lingara apps status [name] [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `name` | string | no |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

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
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

### apps pin

Add an app to platform.toml (and its example), by id or by name.

```sh
lingara apps pin <app> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `app` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--path <dir>` | path | no |  |  | The project directory; needed for a name |
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

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
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

### apps unpin

Remove an app's row from platform.toml; the example changes by review.

```sh
lingara apps unpin <name> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--config <path>` | path | no |  |  | The platform.toml to read; default the nearest at or above this directory |

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 7 | `unavailable` |
| 16 | `platform_toml_secret` |
| 17 | `platform_toml_schema` |
| 130 | `interrupted` |

### apps delete

Delete an app, or one of its versions.

```sh
lingara apps delete <id> [flags]
```

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara apps delete <id> -y --confirm <email>`.

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

### apps submit

Submit a version of an app for review, to make it public.

```sh
lingara apps submit <id> --version <semver> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--version <semver>` | string | yes |  |  | The version to submit |

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

### apps versions list

List an app's versions.

```sh
lingara apps versions list <id> [flags]
```

Table columns: `SEMVER`, `STATE`, `STATE_AT`, `REVIEWED`.

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

### apps versions publish

Publish an approved version, or a private app's draft.

```sh
lingara apps versions publish <id> <semver> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |
| `semver` | string | yes |  |

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

### apps versions withdraw

Withdraw a version.

```sh
lingara apps versions withdraw <id> <semver> [flags]
```

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |
| `semver` | string | yes |  |

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

