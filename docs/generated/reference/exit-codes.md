---
title: "exit codes"
description: "The exit codes every `lingara` variant returns."
order: 7
---

The exit codes every `lingara` variant returns.

| Code | Name | Meaning |
|---|---|---|
| 0 | `ok` | Done. |
| 1 | `failure` | Anything not below: a bug, or a response the CLI did not expect. |
| 2 | `usage` | An unknown command or flag, a missing argument, a bad value, or flags that cannot be combined. |
| 3 | `auth_required` | No stored credentials, a refresh the server refused, or a 401 after one refresh. Run `lingara login`. |
| 4 | `not_found` | The object does not exist, or is not yours. |
| 5 | `refused` | The server refused the request and said why (400, 403, 409 or 422), including a missing scope. |
| 6 | `not_confirmed` | A dangerous command was not confirmed: declined at y/n, a typed email that did not match, or a non-interactive call without `-y --confirm <email>`. |
| 7 | `unavailable` | A network failure, a 5xx, maintenance, or a 429 after the CLI's retries. |
| 8 | `platform_disabled` | The developer platform is not enabled for this account yet. |
| 9 | `tool_missing` | `apps dev` needs cloudflared, and it is not installed or is too old. |
| 10 | `tunnel_failed` | The tunnel never reported a URL or answered within 30 s, or died a sixth time. |
| 11 | `dev_session_held` | Another `apps dev` session holds this app's dev version. |
| 12 | `dev_session_lost` | The dev session was taken over or expired while it ran. |
| 13 | `app_exited` | The app process exited with a non-zero status. |
| 14 | `connection_scripted` | `lingara/connection.js` holds a statement with a script, which only the builder can apply. |
| 15 | `render_url_ephemeral` | A version's renderUrl is a quick-tunnel or dev.invalid address. |
| 16 | `platform_toml_secret` | `platform.toml` holds a value or key that looks like a secret. |
| 17 | `platform_toml_schema` | `platform.toml` has an unknown schema, or does not match the file's JSON Schema. |
| 18 | `app_not_listening` | The app did not accept a connection on its port within 60 s. |
| 130 | `interrupted` | Interrupted (Ctrl-C). |
