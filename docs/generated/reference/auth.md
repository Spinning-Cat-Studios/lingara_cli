---
title: "auth"
description: "The commands outside any topic: login, logout, whoami."
order: 1
---

The commands outside any topic: login, logout, whoami.

Every command also takes the global flags `--json`, `--profile <name>`.

### login

Sign in through the browser, or with a device code.

```sh
lingara login [flags]
```

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--device` | bool | no |  |  | Sign in with a code shown here, for a machine with no browser |

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

Sign in on a machine with no browser:

```sh
lingara login --device
```

### logout

Revoke and forget the stored credentials.

```sh
lingara logout [flags]
```

| Exit | Name |
|---|---|
| 0 | `ok` |
| 1 | `failure` |
| 2 | `usage` |
| 3 | `auth_required` |
| 7 | `unavailable` |
| 130 | `interrupted` |

### whoami

Show the developer the credentials belong to.

```sh
lingara whoami [flags]
```

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

