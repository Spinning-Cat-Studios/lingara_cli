# The lingara CLI contract

Every kit of `lingara` keeps the behaviours below. The words MUST, MUST NOT,
SHOULD and MAY are used as in RFC 2119. Spelling, help-text layout and
framework idiom are each kit's own; what is fixed here is what a developer or
a CI job can **observe**: argv, stdin, the terminal, stdout, stderr, the exit
code, and the requests the kit sends.

The cases under [`cases/`](cases/) make each behaviour executable, and
[`README.md`](README.md) describes how they run. **A kit that cannot pass a
case does not ship.**

The command tree, the exit-code table and the message strings named below
are [`spec/cli.schema.json`](../spec/cli.schema.json) and
[`spec/messages.en.json`](../spec/messages.en.json). Request paths are named
by the logical routes in [`routes.toml`](routes.toml).

## B1 — Streams and output

- Data MUST go to stdout. Prompts, progress, warnings and errors MUST go to
  stderr.
- Without `--json`, a list or a get prints the **human table**: a header row
  of the schema's `output.columns`, in order, then one row per object. Cells
  are left-aligned and padded to the column's widest cell, with exactly two
  spaces between columns, no borders and no trailing whitespace. An empty or
  `null` value renders as `-`, so no cell is blank and the two-space split
  stays unambiguous. A `get` prints a two-column `FIELD  VALUE` table. The
  human table is for people; a script reads `--json`.
- Colour MAY be emitted only when stdout is a terminal and `NO_COLOR` is
  unset. Conformance always sets `NO_COLOR=1` and stdout is always a pipe, so
  this clause is stated rather than judged: a coloured byte in a judged table
  fails the case as any other wrong byte would.
- With `--json`, stdout MUST carry exactly one JSON value and nothing else.
  That value is the server's object or list as the server answered it: a kit
  never renames a field. A list MUST follow every cursor page and print every
  page's items as **one** JSON array, so a script never pages by itself.
- A command with nothing to print (a delete, for example) prints nothing to
  stdout without `--json`, and `{}` with it.
- A long-running command (`apps dev`, ADR 16.10.26ae) keeps all of its
  progress on stderr while it runs, and under `--json` writes its one stdout
  value **once, at exit**, whatever the exit: `{"name", "app_id",
  "session_id", "urls", "exit"}`, where `exit` is the exit-code table's name.

## B2 — Exit codes and refusals

Every exit MUST be one of the schema's exit codes. A kit classifies every
non-2xx answer in this order, and the first rule that matches wins:

1. **B4's three signals** → `platform_disabled` (8). They are checked first,
   because each is also a 4xx that a later rule would otherwise claim.
2. **A credential failure** — a `401` to a request that carried a Bearer
   (every route except `oauth.token` and `oauth.device_authorization`) → the
   kit refreshes **once** and replays the request. If the replay is a `401`
   again → `auth_required` (3); any other answer to the replay is classified
   again from rule 1, with no second refresh. The refresh is itself an
   `oauth.token` request, so its own answer never re-enters this rule: a
   refresh refused with B4's `lingara_code` is `platform_disabled` (rule 1),
   any other envelope refusal is `auth_required` (rule 3), and an outage is
   `unavailable` (rule 7). Under CI credentials "refresh" means minting a new
   token through `client_credentials`. The platform answers a bearer that
   fails validation with `401` and `WWW-Authenticate: Bearer
   error="invalid_token"`, so no 404 is ever read as a credential failure.
3. **An OAuth refusal** — a 4xx other than 429, carrying the RFC 6749 / RFC
   8628 `error` envelope (an `error` member and no `code` member), from
   `oauth.token` or `oauth.device_authorization`, without B4's
   `lingara_code` → `auth_required` (3), never `refused`. This rule never
   claims a 429 or a 5xx, nor an empty 404 or a `{code, error}` body. RFC
   8628's `authorization_pending` and `slow_down` during a device poll are not
   answers to classify: the kit keeps polling.
4. **A `{code, error}` 404** → `not_found` (4), with one exception:
   `404 {"code":"developer_not_found"}` on `developer.me` is `refused` (5),
   because the signed-in person has no developer account rather than a
   missing object.

   4a. **A dev-session `409`** (ADR 16.10.26ae D10), keyed on the route
   **and** the body's `code`, because a script branches on "someone else is
   developing this app":

   | Route | `code` | Exit |
   |---|---|---|
   | `app_dev.session_open` | `dev_session_held` | `dev_session_held` (11) |
   | `app_dev.patch` | `dev_session_held` | `dev_session_held` (11) |
   | `app_dev.session_renew` | `dev_session_not_live`, `dev_session_expired` | `dev_session_lost` (12) |

   Any other `409` on these routes (`version_conflict`, say) is rule 6's.
   `apps dev` remaps `app_dev.patch`'s row to 12 while its session runs:
   there it means the session was taken over since the open.
5. **An empty-body 404** → `failure` (1), "unexpected response from
   {route}". It means the kit and the server disagree about a route, not that
   an object is missing.
6. **Any other 400, 403, 409 or 422** → `refused` (5).
7. **A 429** (after the kit's retries for an idempotent request; at once for
   a `POST`, B5), **a 5xx**, the plain-text maintenance 503, or a connection
   failure → `unavailable` (7).
8. **Any other non-2xx answer** (a 404 whose body is neither empty nor
   `{code, error}`, a 405, 410, 413 or 415, an unexpected 3xx outside the
   browser flow) → `failure` (1), "unexpected response from {route}". The
   list is therefore complete: no kit improvises a classification.

- An argv the kit cannot parse MUST NOT reach the server: it exits `usage`
  (2).
- On a refusal, stderr carries `error: <server error text> (<code>)` on one
  line. With `--json`, stderr instead carries one JSON line,
  `{"error":{"code":"…","message":"…","exit":<n>}}`.

## B3 — The confirmation ritual

1. A command marked `dangerous` in the schema is **interactive** only when
   both stdin and stderr are terminals.
2. **Interactive.** The kit first reads the object, so the prompt can name it
   and what goes with it. It then prints `confirm_yes_no` and accepts only `y`
   or `yes` (case-insensitive); anything else is declined. Next it prints
   `confirm_type_email` and reads one line. The line MUST equal the signed-in
   developer's email after trimming and ASCII case-folding. There is no retry
   loop. Only then does it send the destructive request.
3. **Flagged.** `-y --confirm <email>` replaces both prompts, interactive or
   not. The email is checked against the signed-in developer the same way,
   **before** the destructive request; a mismatch exits `not_confirmed` (6)
   with `confirm_email_mismatch` and sends it nothing. The flagged path sends
   no read for a prompt, and with stored credentials the email check needs no
   request either. So the only requests before the destructive one are,
   under CI credentials, the token mint and `developer.me`, and, on two
   commands, an **id lookup** the destructive request cannot be built
   without: `canvas delete`'s `canvas.lookup`, and `webhook outbound
   replace`'s `webhook_outbound.get` and `webhook_outbound.version_get`.
   `replace` then sends its new endpoint's create before the destructive
   `DELETE`, and both prompts, or the `--confirm` check, complete before that
   create. Every other dangerous command sends its destructive request alone.
   Neither flag alone is confirmation.
4. **Non-interactive without both flags.** Print `confirm_non_tty_refused` and
   exit `not_confirmed` (6) **before any request is sent**, the read included.
5. A declined y/n exits `not_confirmed` (6) with `confirm_declined`; a typed
   email that does not match exits 6 with `confirm_email_mismatch`. Neither
   sends the destructive request.
6. **The email travels with the request**, so the server re-checks it. The
   kit sends the canonical form, trimmed and ASCII-lowercased, never the raw
   keystrokes, as the JSON body field `routes.toml` names
   (`confirm_body_field`: `{"confirm": "<email>"}`, `Content-Type:
   application/json`) on the `DELETE` itself. A mismatch the server finds
   answers `422 delete_confirmation_mismatch`, which B2 maps to `refused` (5).
   The email never goes in a header or a query string. Every destructive
   route is under `/platform`.

## B4 — Platform disabled

- When the developer platform is off for the caller, every developer route
  answers one of the signals below, and `login` is refused through the
  `/oauth/token` spelling.
- The kit prints `platform_disabled` ("The Lingara platform isn't enabled for
  this account yet.") and exits `platform_disabled` (8). It never shows a raw
  404 and never says `not_found`.
- The signals, each recorded in `routes.toml`:
  - on a `/platform/*` route, an authenticated caller not on the flag gets
    `404 {"code": <platform_disabled_code>, …}`. A `404` with any other code
    is still `not_found`;
  - on a JSON door that runs before a caller exists,
    `403 {"code": <platform_access_code>, …}`;
  - on `/oauth/token`, RFC 6749's envelope (`access_denied` for the device
    grant, `invalid_grant` for a code or refresh, `unauthorized_client` for
    client credentials) carrying an extra member `"lingara_code"` equal to
    `platform_access_code`. The same error without that `lingara_code`, or
    with another one, is B2 rule 3, `auth_required`.
- A kit keys every signal on a **code**, never on English text: it never
  classifies on `error` text or `error_description` (it MAY print them in
  B2's stderr line), so a reworded or localised sentence cannot change an exit
  code. At `/oauth/token` the key is `lingara_code == platform_access_code`
  alone, whatever the RFC `error` beside it.
- No kit probes another route to decide: the answers are distinguishable on
  their own.

## B5 — Request hygiene

- Every request MUST carry `User-Agent: lingara-cli/<semver> (<lang>; <os>)`,
  matching this pattern, where `<lang>` is the kit's `languages.toml` id:

  ```
  ^lingara-cli/\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)? \((typescript|rust|go|java|kotlin|ruby|php); [a-z0-9_]+\)$
  ```
- Every request MUST carry `Accept-Language` as the BCP 47 form of `LC_ALL`,
  else `LC_MESSAGES`, else `LANG`, when one is set: conformance's
  `LANG=en_US.UTF-8` arrives as `en-US`.
- Every request MUST carry `Authorization: Bearer <token>`, except to the
  token and device-authorization endpoints.
- No `DELETE` carries a body, except a destructive one's `{"confirm":
  "<email>"}` (B3.6).
- The kit MUST NOT retry a non-idempotent request (`POST`) after a response
  has arrived, with one exception: B2 rule 2's single replay after a `401`,
  and the refresh it triggers, because a request refused for its credential
  was never acted on.
- A **scheduled** request is not a retry (ADR 16.10.26ae D7 step 7): `apps
  dev` renews its lease every `lease_secs / 3`, and a renew that failed on
  the network or answered `429` or a 5xx is simply followed by the next
  scheduled renew, never repeated at once.

## B6 — Authentication

1. **Precedence.** A kit signs in with, in order: CI client credentials, when
   both `LINGARA_PLATFORM_CLIENT_ID` and `LINGARA_PLATFORM_CLIENT_SECRET` are
   set; else the stored credential of the profile (`--profile`, else
   `LINGARA_PROFILE`, else `default`); else nothing, which is
   `auth_required` (3) **before any request**. A kit MUST NOT read an app's
   `LINGARA_CLIENT_ID` / `LINGARA_CLIENT_SECRET`.
2. **CI.** Every command mints a token at `oauth.token` with
   `grant_type=client_credentials`, `scope=platform:read platform:write
   canvas:read canvas:write` and `client_secret_basic`, keeps it in memory
   only, and stores nothing. B2 rule 2's refresh is a fresh mint. A mint
   refused `invalid_scope` is `auth_required` and SHOULD say to recreate the
   client with `lingara client create --ci`. The email `--confirm` is checked
   against is `developer.me`'s, read at most once. `login` and `logout`
   exit `usage` (2) with `login_ci_not_needed`.
3. **Refresh.** A stored access token within 60 s of expiry, or one answered
   `401`, is refreshed with `grant_type=refresh_token`, the stored refresh
   token and the CLI's public `client_id`, and the record is rewritten with
   the new tokens, the answer's `scope` and the new expiry. A refused refresh
   MUST NOT delete the stored credential.
4. **`login`** asks for the four scopes in one grant, and on success reads
   `developer.me`, stores the profile record and prints `login_signed_in` on
   stderr.
   - **PKCE loopback (default, RFC 8252).** The kit listens on `127.0.0.1`
     on a port of its choosing, prints the portal's `oauth.authorize` URL
     (`response_type=code`, the public `client_id`, `redirect_uri`
     `http://127.0.0.1:<port>/callback`, an S256 `code_challenge`, a random
     `state`, the four scopes) on stderr, then `login_browser_wait`, and opens
     the browser unless `LINGARA_NO_BROWSER=1`. It accepts exactly one
     callback whose `state` matches and whose `iss` equals the portal base
     (RFC 9207), answers it with a page saying the tab may close, and
     exchanges the code with its `code_verifier`. A callback carrying an
     `error` is `auth_required`; no callback within 5 minutes is
     `auth_required`.
   - **`--device` (RFC 8628).** The kit posts the public `client_id` and the
     scopes to `oauth.device_authorization`, prints `login_device_code` on
     stderr, waits `interval` before each poll of `oauth.token`, keeps polling
     on `authorization_pending`, adds 5 s on `slow_down`, and exits
     `auth_required` on `expired_token` or `access_denied`. Any answer
     carrying B4's `lingara_code` is `platform_disabled`.
5. **`logout`** revokes the refresh token at `oauth.revoke` (RFC 7009) and
   deletes the stored credential. A revoke that fails still deletes it, and
   warns on stderr.
6. **The credential file.** Where the kit uses a file (always under
   `LINGARA_KEYRING=file`, which conformance sets), it is
   `<config dir>/credentials.json`, mode `0600` in a `0700` directory,
   shaped `{"schema": 1, "profiles": {"<profile>": {developer_id, email,
   scopes, refresh_token, access_token, access_expires_at}}}`, with
   `access_expires_at` in RFC 3339. The config dir is `LINGARA_CONFIG_DIR`,
   else `$XDG_CONFIG_HOME/lingara`, else `~/.config/lingara`, and
   `%APPDATA%\lingara` on Windows. Secrets MUST NOT be written to
   `config.toml`. `fixtures/credentials/stored.json` is this shape.

## B7 — Canvas apply

1. **A draft creates nothing.** `canvas draft` reads the canvas and saves the
   definition with `canvas.put_draft` at the draft's `draft_version` (`0`
   with none). It sends no other request.
2. **Read.** `canvas apply` reads the canvas and every page of the
   `oauth_client`, `webhook_outbound` and `app` lists. With no draft it is
   `refused` (5). Each recorded runtime id is joined to a list row by
   `runtime_id`; a record is kept only while its row is live **and** still
   under the recorded client (a secret while its client is).
3. **Plan.** The kit plans every node in topological order (Kahn's
   algorithm, ties broken by node order): a kept record is `update`, anything
   else `create`, and a recorded secret is not planned at all. Bodies and
   ops are `vectors/canvas-plans.json`'s, which every kit's tests read. A
   node with `custom: true` runs a script, so the plan is `refused` naming
   that node, **before the dry-run and with zero writes**.
4. **Dry-run.** The plan goes to `canvas.dry_run`. Any `refused` verdict stops
   apply, naming every refusal; a `would_replace` stops it naming the node.
   Neither writes anything.
5. **Apply** runs this CLI's **own verbs**, in plan order, in-process: a
   create op is that kind's `create`; an `update` whose verdict names no
   `changes` makes no call; a `would_update` webhook is one `webhook
   outbound update` carrying exactly the flags its `changes` name (`--url`,
   `--event`, `--enable`); a secret noted `minted_with_client` makes no call
   and records the secret its client's create minted. Apply records each
   object's **`runtime_id`**, re-reading the object once a second, at most
   ten times, while it is `null`, and a secret's id, never a platform object
   id. A `POST` answered 429 is not retried.
6. **Activate.** `canvas.activate` with the draft's `draft_version`,
   `outcome` `complete`, and the recorded runtime ids. At the first failing
   op it activates `partial` with what was recorded, prints what it made and
   every secret minted so far on stdout, prints `canvas_apply_partial`, and
   exits with the failing op's code. An activate answered `409
   canvas_conflict` is `refused` (5).
7. **`--print-commands`** runs steps 2–4 and prints step 5's argvs as shell
   lines, an id made earlier in the run as `<handle>`. **`--dry-run`** runs
   steps 2–4 and prints the verdicts. Neither sends a write.
