# App templates

ADR 16.10.26ae D1–D3. `lingara apps create <name>` writes a template from
[`templates/apps/`](../templates/apps/) and pins it in `platform.toml`. Every
kit implements this page identically, so every CLI writes a **byte-identical**
tree for the same arguments; conformance compares each scaffold with
`conformance/golden/apps-create/<lang>[-bare]/` byte for byte.

## Where a template is

`templates/apps/<lang>/<variant>/`, where `<lang>` is one of the seven
app-kit ids in the app SDK's `languages.toml` (`typescript`, `rust`, `go`,
`java`, `kotlin`, `ruby`, `php`) and `<variant>` is `default` or `bare`
(`--bare`). A kit carries the tree in its own `assets/templates/apps/`
(D12), copied by `make sync-kit-assets`.

Each template directory holds:

- **`template.toml`**, read by the CLI and never written: `start`, the
  `platform.toml` row's start command, and exactly one of `manifest` (the
  default variant's `lingara/connection.js`) or `manifest_command` (the bare
  variant's, D3).
- every other file, written to the project at the same relative path, with
  two renaming rules, both because a published kit must carry the file:
  - a path segment that begins with `_` is written with `.` instead
    (`_gitignore` is written as `.gitignore`): npm drops `.gitignore` from a
    package;
  - a file name that ends in `.tmpl` is written without it (`Cargo.toml.tmpl`
    is written as `Cargo.toml`): `cargo package` and a Go module zip skip any
    directory holding its own `Cargo.toml` or `go.mod`.

Files are UTF-8 text with LF line endings, written mode `0644`; directories
are created as needed. The order files are written in is not observable.

## Substitution

Only `{{key}}` with a key from this fixed set is replaced, in file contents
(never in paths). Any other `{{…}}` is left as written. Each key has one
derivation:

| Key | Value |
|---|---|
| `name` | the argument, which must match `^[a-z][a-z0-9-]{0,39}$`, else exit `usage` with message `app_name_invalid` |
| `display_name` | `name` with each `-` replaced by a space and each word's first ASCII letter upper-cased: `luba-dive-log` → `Luba Dive Log` |
| `description` | `--description`, default `A Lingara app.` It may not contain `"`, `\`, `` ` ``, `$`, `#`, `{`, `}` or a control character (exit `usage`), so it is safe inside a string literal in every template language |
| `port` | `--port`, default `8787`, written in decimal |
| `slots` | `--slot`, in argument order, default `home.side` |
| `context` | `--context`, in argument order, default none; each must match `^[a-z][a-z_]*$` (exit `usage`) |

`slots` and `context` are rendered as an **array literal** of double-quoted
strings in argument order, separated by `, ` (comma and one space), with no
trailing comma. The syntax is JavaScript's in a default template, whose only
use is `lingara/connection.js`, and the template language's own in a bare
one:

| Where | Two values | None |
|---|---|---|
| `default` (every language), and `bare` for `typescript`, `rust`, `ruby`, `php` | `["home.side", "plans.empty_detail"]` | `[]` |
| `bare` for `go` | `[]string{"home.side", "plans.empty_detail"}` | `[]string{}` |
| `bare` for `java` | `List.of("home.side", "plans.empty_detail")` | `List.of()` |
| `bare` for `kotlin` | `listOf("home.side", "plans.empty_detail")` | `listOf()` |

The app id is **not** a key: it lives only in `platform.toml`, so the tree is
the same with or without registration.

## The scaffold, step by step

1. `--dir` (default `./<name>`) is refused with `usage` and message
   `dir_not_empty` when it exists and holds anything, before anything is
   written.
2. The template's files are written into it.
3. **`platform.toml`.** The CLI walks up from the **parent** of `--dir`
   looking for `platform.toml`, as Cargo does for `Cargo.toml`, stopping at
   the filesystem root. With none found, it creates `<dir>/platform.toml`.
   Either way it appends the project's row (below) with `path` relative to
   that file's directory (`.` when the file is inside `--dir`), using `/`
   between segments on every platform.
4. **`platform.toml.example`** beside that file gains the same row, with the
   placeholder `id`, when it has no row of that `name`; it is created when it
   does not exist.
5. **`.gitignore`** in the same directory as `platform.toml` gains a line
   `platform.toml` when no line of it, trimmed, equals that; it is created
   when it does not exist. A file that does not end in a line break gets one
   first.

## Writing `platform.toml` and its example

A new `platform.toml` starts with exactly these three lines:

```toml
# platform.toml — gitignored; copy from platform.toml.example.
# Never put a secret here. lingara refuses to read a value that looks like one.
schema = 1
```

A new `platform.toml.example` with exactly these three:

```toml
# platform.toml.example — checked in. Copy it to platform.toml (gitignored),
# then run lingara apps update to register your own private copy of each app.
schema = 1
```

A **row** is appended as one blank line, then these lines, in this order,
each `key = value` with one space either side of `=`:

```toml
[[apps]]
name = "<name>"
id = "<id>"                  # only when there is one; the example always has one
path = "<path>"
port = <port>
start = "<start>"
manifest = "<manifest>"      # or manifest_command = "<command>", per template.toml
```

The example's `id` is the placeholder `00000000-0000-0000-0000-000000000000`,
which every kit reads as **no id**: a teammate who copies the example and
runs `lingara apps update` registers their own object (D6 step 4).
Registration writes the id into an existing row by inserting the `id` line
directly after the row's `name` line, or by replacing the row's `id` line.
`lingara apps unpin` removes a row from its `[[apps]]` line up to, not
including, the next line that opens a table other than `[apps.env]`; when the
row runs to the end of the file, the blank lines before it go too, so the
file never ends in blank lines it did not have. Strings are TOML basic
strings: `"` and `\` are escaped with `\`.
