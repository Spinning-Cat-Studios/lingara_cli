# Developing a Lingara app from your laptop

`lingara apps dev` serves your app from this machine to your own install of
it, behind a tunnel, until you press Ctrl+C. Nobody else ever renders your dev
version, so a crash, a restart or a closed laptop costs no learner anything.

## The walk

```sh
lingara login                         # once, in the browser (or --device)
lingara apps create luba              # scaffold ./luba and register it, private
cd luba && npm install                # the template's own dependencies
lingara apps dev                      # tunnel up, dev version pointed at it, app started
# … edit, reload, render …            # Ctrl+C parks the URL and stops everything
lingara apps update                   # push lingara/connection.js to your dev version
```

`apps create` writes a template in the language you choose with `--lang`
(`typescript`, `rust`, `go`, `java`, `kotlin`, `ruby`, `php`). Its objects
live in `lingara/connection.js`, the same file the builder's Code tab opens;
its card lives in the app's own code. `--bare` writes the manifest as code
instead, and the platform reads what its `manifest_command` prints.

`apps dev` needs [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/),
a free binary that needs no Cloudflare account (`brew install cloudflared`,
`winget install --id Cloudflare.cloudflared`). It is never downloaded for you.
With a tunnel of your own, pass `--tunnel none --render-url https://…`.

## What happens when you run `apps dev`

1. It opens a **dev session** on your app. The session mints a signing secret
   (and, if your row asks, an OAuth client secret) that live only in the CLI's
   memory and your app's environment, and stop working when the session ends,
   or after 12 hours at most.
2. It starts your app with `start` from `platform.toml`, in its own process
   group, and waits for it to listen on `port`.
3. It opens a quick tunnel, waits until a request reaches your app through
   it, and points your **dev version** at the tunnel's URL. The line
   `luba → https://….trycloudflare.com/ (dev version; only you can install it)`
   means it is live.
4. It renews the session's lease every 100 seconds. If cloudflared dies, it
   starts a new one and points the dev version at the new URL, up to five
   times.
5. On Ctrl+C it parks the session first, so the next render answers
   "unavailable" at once instead of waiting on a dead tunnel, then stops the
   tunnel and your app.

`lingara apps status` shows where your dev version renders from: a live
session and its lease, a parked one and the last URL, or the serving version.
`lingara apps dev --reset` puts your install back on the serving version.

## platform.toml

`platform.toml` is per developer and gitignored; `platform.toml.example` is
checked in with placeholder ids. A teammate runs
`cp platform.toml.example platform.toml && lingara apps update` and gets a
private copy of each app of their own.

```toml
schema = 1

[[apps]]
name = "luba"                    # `lingara apps dev luba`
id = "…"                         # written by apps create / apps update / apps pin
path = "apps/luba-dive-log"      # relative to this file
port = 8787
start = "npm start"              # run through your shell, in `path`
manifest = "lingara/connection.js"
credentials = ["app_secret", "client_credentials"]
env_file = ".env.local"          # your own third-party secrets; must be gitignored

[apps.env]                       # non-secret values for the app
LUBA_PLAN_ID = "…"
```

`lingara` refuses a `platform.toml` that holds anything shaped like a secret,
naming the key and never the value.

## Where secrets go

| Secret | Lives | Reaches your app |
|---|---|---|
| Your `lingara login` session | the OS keychain, or a `0600` file where there is none | never: `LINGARA_PLATFORM_*` is removed from the app's environment |
| CI's platform credentials | your CI's secret store, as `LINGARA_PLATFORM_CLIENT_ID` / `_SECRET` | never (the same scrub) |
| The app's signing secret, and its OAuth client pair if `credentials` asks | minted per session, in the CLI's memory only | as `LINGARA_APP_SECRET`, `LINGARA_CLIENT_ID`, `LINGARA_CLIENT_SECRET`; revoked when the session ends |
| Your own third-party secrets | your environment, or `env_file` | inherited or loaded; the CLI never writes them |

Nothing is written to disk unless you pass `--write-env <file>`, which must be
gitignored and is written mode `0600`. `--show-env` lists variable names only.

## Shipping a version

The dev version is yours alone. When the app has a real address, set
`renderUrl` in `lingara/connection.js` and cut a version:

```sh
lingara apps update --version 1.0.0           # an immutable draft from the project's manifest
lingara apps versions publish <id> 1.0.0      # a private app publishes at once
lingara apps submit <id> --version 1.0.0      # a public one goes through review first
```

A version whose `renderUrl` is a quick tunnel or the template's
`https://dev.invalid/` is refused before any request: nothing else would
catch a published version pointing at a tunnel that is gone.
