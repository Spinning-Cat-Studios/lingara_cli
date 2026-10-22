---
title: "oauth_client"
description: "OAuth clients: the credentials your servers, apps and CI use."
order: 4
---

OAuth clients: the credentials your servers, apps and CI use.

Alias: `client`.

Every command also takes the global flags `--json`, `--profile <name>`.

### oauth_client create

Create an OAuth client; a confidential one's secret is shown once.

```sh
lingara oauth_client create --name <name> [flags]
```

Also spelled `lingara client create`.

At most one of `--ci`, `--type` may be given.

At most one of `--ci`, `--scope` may be given.

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | yes |  |  | The client's name |
| `--scope <scope>` | string, repeatable | no |  |  | A scope the client may ask for |
| `--type <type>` | enum: `confidential`, `public` | no | `confidential` |  | Whether the client keeps a secret |
| `--billing-mode <mode>` | enum: `allowance`, `metered` | no | `allowance` |  | How its usage is billed |
| `--redirect-uri <uri>` | string, repeatable | no |  |  | A redirect URI it may use |
| `--ci` | bool | no |  |  | A CI client: confidential, holding the four platform and canvas scopes |

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

A client for CI to sign in with:

```sh
lingara client create --name 'Release pipeline' --ci
```

### oauth_client list

List your OAuth clients.

```sh
lingara oauth_client list [flags]
```

Also spelled `lingara client list`.

Table columns: `ID`, `NAME`, `TYPE`, `SCOPES`, `CREATED`.

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

### oauth_client get

Show one OAuth client.

```sh
lingara oauth_client get <id> [flags]
```

Also spelled `lingara client get`.

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

### oauth_client update

Rename an OAuth client, or replace its scopes, API version or redirect URIs.

```sh
lingara oauth_client update <id> [flags]
```

Also spelled `lingara client update`.

| Argument | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
| `--name <name>` | string | no |  |  | The new name |
| `--scope <scope>` | string, repeatable | no |  |  | Replaces the scopes |
| `--api-version <version>` | string | no |  |  | The API version it is pinned to |
| `--redirect-uri <uri>` | string, repeatable | no |  |  | Replaces the redirect URIs |

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

### oauth_client delete

Delete an OAuth client and everything under it, or one version.

```sh
lingara oauth_client delete <id> [flags]
```

Also spelled `lingara client delete`.

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara oauth_client delete <id> -y --confirm <email>`.

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

Delete from CI:

```sh
lingara client delete oc_123 -y --confirm dev@example.com
```

### oauth_client secret create

Mint a new secret, shown once. The old one stays live.

```sh
lingara oauth_client secret create <client_id> [flags]
```

Also spelled `lingara client secret create`.

| Argument | Type | Required | Description |
|---|---|---|---|
| `client_id` | string | yes |  |

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

Start a rotation:

```sh
lingara client secret create oc_123
```

### oauth_client secret delete

Revoke a secret.

```sh
lingara oauth_client secret delete <client_id> <secret_id> [flags]
```

Also spelled `lingara client secret delete`.

> **Dangerous.** This command deletes or replaces something, so it asks twice: a y/n question, then your developer email typed back. Where nobody can answer, as in CI, pass both flags instead: `lingara oauth_client secret delete <client_id> <secret_id> -y --confirm <email>`.

| Argument | Type | Required | Description |
|---|---|---|---|
| `client_id` | string | yes |  |
| `secret_id` | string | yes |  |

| Flag | Type | Required | Default | Env | Description |
|---|---|---|---|---|---|
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

