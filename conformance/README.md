# Conformance

Every kit of `lingara` keeps one contract, [`CONTRACT.md`](CONTRACT.md). This
directory makes it executable, and it drives the **binary**, not a library:
a CLI's public interface is its argv, stdin, terminal, stdout, stderr and
exit code, which are the same in every language.

- [`cases/`](cases/) — one YAML file per case, grouped `b1`…`b7` (one group
  per behaviour), `op` (per command) and `apps`;
- [`routes.toml`](routes.toml) — every platform route a case calls, by a
  logical name;
- [`fixtures/`](fixtures/) — the stored credentials a `credentials: stored`
  case starts with, and the files a verb reads (`manifests/`, `canvases/`),
  named in argv as `{fixtures}/…`; the `apps` group's `projects/` and its two
  fakes (ADR 16.10.26ae D11);
- [`golden/`](golden/) — the trees `apps create` writes, which every kit's
  scaffold matches byte for byte (`expect.files.golden`);
- [`vectors/`](vectors/) — `canvas-plans.json`, canvas apply's plan rules,
  which every kit's unit tests read (B7);
- [`runner/`](runner/) — `cli-conformance`, a Rust crate that runs a mock
  platform, spawns the kit once per case, and judges what came back.

```sh
make check-conformance-coverage   # every case parses; every command and ritual has one
make conformance                  # every kit, every case
make conformance-rust             # one kit
make test-conformance             # the runner's own tests
```

A kit joins with one make variable in its own `make/<lang>.mk`:

```make
CONFORMANCE_CMD_typescript = $(shell mise which node) $(CURDIR)/typescript/bin/run.js
CONFORMANCE_PASS_ENV_typescript =          # toolchain variables its launcher needs, if any
```

The command is **absolute** and names the **resolved** interpreter, not a
version-manager shim: the child runs in the case's temp dir with a fresh
`HOME`, where neither a relative path nor a shim's config resolves.

## Adding a case

Write `cases/<group>/<slug>.yaml`; its `id` MUST be `<group>.<slug>`. The
schema is closed (unknown keys are refused), and
`make check-conformance-coverage` parses every file. **Adding a case needs no
ADR; adding a key to the schema does.** Case credentials are visibly fake:
`lgr_cid_conformance…`, `lgr_cs_conformance…`, `lgr_at_conformance…`, and the
developer `dev@conformance.invalid`.

```yaml
id: <group>.<slug>
title: <one sentence>
behaviours: [B3, B5]             # ≥ 1, of B1–B7
command: oauth_client delete     # the canonical schema path covered; absent only when
                                 #   argv resolves to no command (then exit must be usage)
confirm: tty_ok                  # dangerous commands only: tty_ok | tty_declined | tty_mismatch |
                                 #   non_tty_refused | non_tty_flags | non_tty_flags_mismatch
argv: [client, delete, <id>]     # aliases allowed; must resolve to `command`
tty: true                        # default false
stdin: ""                        # non-tty only: written, then closed
env: {}                          # LINGARA_* names only, never one the runner sets
credentials: stored              # none | stored | ci
interact:                        # in order
  - expect: { message: confirm_yes_no, vars: { name: My server } }   # or { contains: "<text>" } (tty only)
    send: "y\n"                                                      # (tty only)
  - browser: follow_url          # GET the first URL on stderr, follow redirects (any case)
expect:
  exit: ok                       # a schema exit name, never a number
  stdout: { empty: true }        # or { json: … } | { table: { columns: [...], rows: [[...]] } } | { contains: [...] }
  stderr: { message: <id> }      # or { json_error: { code: …, exit: … } } | { contains: [...] } | { empty: true }
  redacted: [<secret>…]          # never on stderr; on stdout only where expect.stdout names it
  stored_credentials: present    # present | absent, in LINGARA_CONFIG_DIR after exit
exchanges:
  order: sequence                # sequence | any; default sequence
  items:
    - route: oauth_client.get    # a routes.toml name; or request.method + request.path
      vars: { id: <id> }         # fill {name} in the route's path
      times: <n>                 # default 1
      group: <n>                 # consecutive items sharing a group match in any order
      min_gap_ms: <n>            # since the previous match; arriving earlier is a mismatch
      request:
        query: {…}               # exact set beside the route's own ?…; a subset on a redirect item
        headers: { <lowercase name>: { equals | prefix | contains | pattern: <str> } | { absent: true } }
        json: {…}                # JSON-equal
        form: {…}                # form fields, exact set
      response:
        delay_ms: <n>
        status: <int>            # default 200; 302 beside redirect
        headers: {…}
        json: {…}                # or text: <str>; neither is an empty body
        redirect: { to_query: redirect_uri, params: { code: c1 }, echo: [state] }
```

- `message` names an id in `spec/messages.en.json`. Its `{placeholders}` are
  filled from `vars`, and any left unfilled match any text, so a kit's own
  hint or cascade sentence may sit inside or after it. Both `message` and
  `contains` are substring matches.
- In an expected or replayed string, `{base_url}` is the mock's URL, and
  `{platform_access_code}` and `{platform_disabled_code}` are `routes.toml`'s
  values; a `redirect`'s `params` are filled too (an RFC 9207 `iss` is
  `{base_url}`).
- In `argv`, `{fixtures}` is this directory's `fixtures/`, absolute, so a
  verb that reads a file (`--manifest`, `--file`) names a committed one from
  any temp dir.
- JSON is compared after dropping keys whose value is `null`. A `table`
  splits each stdout line on runs of two or more spaces, compares columns and
  rows exactly, and fails a layout that does not re-render byte for byte.
- `json_error` reads stderr's one JSON line; its `exit` may be a name.

### The `apps` group's keys (ADR 16.10.26ae D11)

An `apps` case may add these keys, and only an `apps` case: `check` refuses
each of them in any other group, so every other case keeps the replay-only
model above.

```yaml
mock: platform_dev               # the app_dev.* routes come from a stateful model of
                                 #   Backend 16.10.26v §12 (runner/src/mock/platform_dev.rs);
                                 #   every other route still comes from `exchanges`
mock_seed:                       # the model's starting state
  lease_secs: 3                  # what an open answers; default 300
  held_until: "2026-10-20T15:00:00Z"   # another terminal holds a live lease: the first
                                 #   open without take_over answers 409 dev_session_held
  renew: [ok, ok, not_live]      # successive renew answers (ok | not_live | expired),
                                 #   then ok for ever
  version_conflicts: 1           # the first n app_dev.patch calls answer 409 version_conflict
  render_url: "https://…/"       # the dev row's starting render_url; absent: the serving version
project: dev-basic               # or { slug: dev-basic, app: { exit_after_ms, status, listen } }:
                                 #   fixtures/projects/<slug>/ copied into the case's directory,
                                 #   `{free_port}` in its platform.toml filled, and the CLI run there
tunnel: fake                     # | missing | { fake: { die_after_ms: [...], ready_after_ms, answer_530_first } }
signal: { after: { message: apps_dev_ready, count: 1 }, send: SIGINT }   # | SIGTERM
exit_timeout_secs: 90            # 30–90; replaces EXIT_TIMEOUT for this case
expect:
  calls:                         # the platform_dev call log, as an ordered subsequence
    - route: app_dev.patch
      json: { render_url: "https://conformance-1.trycloudflare.com/", session_id: …, draft_version: 1 }
      count: { min: 2, max: 2 }  # bounds one entry's run of consecutive matches;
                                 #   { min: 0, max: 0 } says the call never comes
  child_env:                     # the fake app's record of its environment
    present: [LINGARA_APP_SECRET]
    absent_prefix: [LINGARA_PLATFORM_]
    equals_session: [LINGARA_APP_SECRET]   # its hash is the minted secret's
  files:
    golden: apps-create/typescript   # each top-level entry of golden/<dir>/ against the case's
                                 #   directory, byte for byte, both ways; a `_name` segment
                                 #   there is `.name` here, so a golden .gitignore stays inert
    present: [demo/platform.toml]
    absent_text: ["…"]           # in no file under the case's directory
```

- Everything `platform_dev` mints is deterministic, so a case can name it:
  session n's id is `5e55a0c0-0000-4000-8000-<n, 12 digits>`, its app
  secret `lgr_whsec_conformanceSession<n>Secret` and its client secret
  `lgr_cs_conformanceSession<n>Client`. Every one of them is checked absent
  from every file under the case's directory whether or not the case asks.
- **The fakes** are this crate's own binaries, built beside the runner by
  `make build-conformance-fakes` (which every `conformance-<lang>` runs
  first). `fixtures/fake-cloudflared/` answers `--version` with
  `spec/tools.toml`'s floor, serves `/quicktunnel` (`conformance-<k>.trycloudflare.com`
  for incarnation k) and `/forward/*` on its `--metrics` address, prints the
  banner, and dies or delays on its schedule. `fixtures/fake-app/` listens on
  `PORT`, answers a POST with a card, and records its variables' names and a
  SHA-256 of each `LINGARA_*` value. Both write their pid under the case's
  directory.
- **The runner sets** `LINGARA_CLOUDFLARED`, `LINGARA_TUNNEL_PROBE_URL`
  (`http://{metrics}/forward/` under `tunnel: fake`), `LINGARA_FAKE_CLOUDFLARED`,
  `LINGARA_CONFORMANCE_FAKE_APP` (which a project's `start` names),
  `LINGARA_FAKE_APP` and `LINGARA_CONFORMANCE_CHILD_ENV`, so a case's `env`
  may set none of them.
- A `DELETE` item may carry a body only on a `destructive` route.
- A `redirect` answers `302` to the request's `to_query` parameter with
  `params` added and each `echo` parameter copied: an authorize endpoint.
  Because that request carries values minted per run (a loopback port, a
  PKCE challenge, a state), a redirect item's `request.query` is a subset.

## How a case runs

Per case, `cli-conformance run`:

1. starts the mock on a free `127.0.0.1` port and arms the case's exchanges;
2. makes a fresh temp dir and builds the environment **from scratch**:
   `PATH` and the `--pass-env` names only, then `HOME` and
   `LINGARA_CONFIG_DIR` (the temp dir), `LINGARA_API_URL` and
   `LINGARA_PLATFORM_URL` (the mock), `LINGARA_KEYRING=file`,
   `LINGARA_NO_BROWSER=1`, `NO_COLOR=1`, `LANG=en_US.UTF-8`, `TERM=dumb`, and
   the case's `env`. `CI` never reaches the kit. `--pass-env` refuses `HOME`,
   `CI`, `NO_COLOR`, `TERM`, `LANG`, any `LC_*` and any `LINGARA_*`;
3. seeds `credentials`: `stored` copies `fixtures/credentials/stored.json` to
   `credentials.json`, mode `0600` in a `0700` directory; `ci` sets
   `LINGARA_PLATFORM_CLIENT_ID` / `_SECRET`; `none` seeds nothing;
4. spawns `CONFORMANCE_CMD` plus `argv` in the temp dir. `tty: false` is
   three pipes. `tty: true` makes **stdin and stderr one pseudo-terminal**
   (the child's controlling terminal) and stdout a pipe, so prompts are
   interactive and data stays separately judgeable;
5. waits for each `interact.expect` up to **`INTERACT_TIMEOUT` (5 s)**, and
   for exit up to **`EXIT_TIMEOUT` (30 s)** from the spawn. A timeout is a
   failure, never a hang. A case cannot change either, except that an `apps`
   case's `exit_timeout_secs` (30–90, ADR 16.10.26ae D11) replaces
   `EXIT_TIMEOUT` for that case;
6. judges `expect` and the invariants, and appends one line to
   `LINGARA_CONFORMANCE_OUT` when it is set:

```json
{"case":"b3.tty-ok","lang":"rust","cli_version":"0.1.0","result":"pass","mismatches":[],"duration_ms":41}
```

`cli_version` is read from the kit's first `User-Agent`, `null` when it sent
no request. `run` exits `0` when every case passes, `1` when one fails and
`2` on input it cannot run. `LINGARA_CONFORMANCE_ONLY=<id>,<id>` narrows a
local run and is refused when `CI` is set. Pseudo-terminals make macOS and
Linux the conformance platforms; Windows is not one.

## The invariants

Judged on every case, whether or not it asks:

| # | Invariant |
|---|---|
| I1 | **No destructive call without confirmation.** On a dangerous command, a request to a `destructive` route fails the case unless argv carries `-y`/`--yes` and a `--confirm` matching `dev@conformance.invalid` (trimmed, ASCII case-folded), or the case is `tty: true` and the request came after the last `interact` send; and its JSON body's `confirm_body_field` MUST be exactly the canonical email. `non_tty_refused`, `non_tty_flags_mismatch`, `tty_declined` and `tty_mismatch` send no destructive request; `non_tty_refused` sends no request at all |
| I2 | A request no exchange expects is answered `599` and fails the case |
| I3 | Every request's `User-Agent` matches B5's pattern |
| I4 | Under `--json`, stdout is exactly one JSON value, or empty when the exit is not `ok` |
| I5 | The exit code is in the schema's exit table |
| I6 | No `redacted` string, and no `lgr_cs_` / `lgr_at_` / `lgr_whsec_` / refresh-token value the mock issued (`platform_dev`'s minted session secrets included, ADR 16.10.26ae D11), appears on stderr, the app's prefixed output included; on stdout only where `expect.stdout` names it |

After every case, each fake that wrote a pid under the case's directory is
checked: one still running is killed and fails the case, so a kit that
orphans cloudflared or the app cannot pass (ADR 16.10.26ae D11).

## Coverage

`check-coverage` fails unless every case parses and names only things that
exist (its command, exits, routes, messages; its `argv` resolves to its
`command`; its exit is one the command can return), and the suite has a
`tty_declined`, a `tty_mismatch` and a `non_tty_flags_mismatch` case, B4's
four cases (a `login` and a developer route expecting `platform_disabled`, a
coded 404 expecting `not_found`, a token refusal expecting
`auth_required`), B2's three (a refresh expecting `ok`, a second `401`
expecting `auth_required`, an empty 404 expecting `failure`), and a case
citing every behaviour `CONTRACT.md` writes out.

With `--strict`, which make passes once `LANGS` names a kit, it also fails a
schema command with no case, a dangerous command missing any of
`non_tty_refused`, `non_tty_flags` and `tty_ok`, and a `conflicts` set with
no case passing two of its flags and expecting `usage` with no request.
Without it those three warn. `make check-conformance-coverage` first fails
any language in `LANGS` with no `CONFORMANCE_CMD_<lang>`.

## The runner's own tests

`runner/src/bin/fake_cli/` is a tiny scriptable stand-in for a kit. With an
empty script it keeps the contract for the commands the committed cases use;
each `faults` entry in its script breaks one rule, so the tests prove both
that every committed case passes a conforming kit and that each one bites.
It is not a kit.
