# The connection literal subset

ADR 16.10.26ae D2. `lingara apps update` and `lingara apps dev` read an app's
manifest from the project's `lingara/connection.js`, a `lingara connection v1
(schema 2)` file the builder's Code tab also opens. The CLI never evaluates
JavaScript, so it reads only the **literal subset** of that grammar: every
statement a single `lingara.<kind>({…})` call whose options are literals.
Every kit implements this page, and the fixtures under
[`connection-fixtures/`](connection-fixtures/) hold all seven to one answer.

## The file

```
file       = header NL { blank-line | statement NL }
header     = "// lingara connection v1 (schema 2)"
statement  = "const" handle "=" "lingara." kind "(" value ")"
kind       = "client" | "secret" | "webhook" | "app"
handle     = identifier
```

- The header is the file's first line, byte for byte. Any other first line is
  refused (`header`), so a future schema is refused by its version, never by
  its syntax.
- Whitespace (space, tab, CR, LF) separates tokens and is otherwise ignored.
  A statement may span lines, as the builder prints long ones.
- There are **no comments** outside a script (`comment`): the builder rewrites
  everything outside its scripts, so a comment there would not survive.
- Each `handle` is declared once (`handle`).

## Values

```
value   = string | number | "true" | "false" | "null" | array | object | reference
array   = "[" [ value { "," value } [ "," ] ] "]"
object  = "{" [ member { "," member } [ "," ] ] "}"
member  = key ":" value
key     = identifier | string
reference = identifier            ; an earlier statement's handle
```

- A **string** is in double or single quotes and uses only JSON's escapes
  (`\" \\ \/ \b \f \n \r \t \uXXXX`, plus `\'` in a single-quoted string). A
  raw tab or line break inside one, a template literal and `${…}` are refused
  (`text`).
- A **number** is a JSON number that is finite (`number`).
- A **key** appears once per object (`key`).
- An `identifier` is `[A-Za-z_$][A-Za-z0-9_$]*`. As a value it must name a
  statement declared above it (`reference`); `true`, `false` and `null` are
  values, not references.

## Scripts are refused

A statement whose call carries a second argument (`, function …`) runs a
script. The CLI refuses the whole file at the **first** such statement with
exit `connection_scripted` and message `connection_scripted`, naming the
statement's handle, and reads nothing after it. Apply a scripted connection in
the builder, or with `lingara canvas apply`.

## The app's manifest

`apps update` and `apps dev` need exactly one `lingara.app` statement
(`app_count` otherwise). Its options are `client` (a reference) and `manifest`
(an object); any other option is refused (`option`). The manifest object is
converted to the wire manifest by this **closed key map**, applied
recursively only where it names a nested key. A key the map does not name is
refused (`manifest_key`), never passed through.

| Literal key | Wire key | Value |
|---|---|---|
| `name` | `name` | required; a locale map |
| `description` | `description` | required; a locale map |
| `renderUrl` | `render_url` | required; a string |
| `slots` | `slots` | required; an array of strings |
| `context` | `context` | an array of strings; absent, `[]` |
| `scopes` | `scopes` | an array of strings; absent, `[]` |
| `tutorNote` | `tutor_note` | `true` or `false`; absent, `false` |
| `defaultLocale` | `default_locale` | a string; absent, `"en"` |
| `longDescription` | `long_description` | a locale map; absent, absent |
| `developer` | `developer` | an object: `name` → `name`, `url` → `url`, `supportUrl` → `support_url`; absent, absent |
| `images` | `images` | an array of objects: `sha256`, `role` and `alt` (a locale map) unchanged; absent, absent |

A **locale map** is a value, not a level: a string means
`{"<default_locale>": string}`, and an object of strings passes its BCP 47
tags through unchanged. A required key that is missing is refused
(`manifest_key`).

The wire manifest's members are written in this order, which is
`lingara.manifest`'s in Generator's `v1.ts`: `manifest_version` (always `1`),
`default_locale`, `name`, `description`, `render_url`, `slots`, `context`,
`scopes`, `tutor_note`, then `long_description`, `developer` and `images`
when present. A field absent from the literal is absent from the wire
manifest, so a template without listing fields produces the same bytes as
before 16.10.26ai.

## Refusals

Every refusal other than `connection_scripted` exits `usage` with the error
code `connection_literal` and the text `<file>:<line>:<column>: <what>`, where
line and column are 1-based and count characters. The `<what>` words are each
kit's own; the fixtures pin the exit, the code and the position.

| Code | When |
|---|---|
| `header` | the first line is not the header |
| `comment` | a `//` or `/*` outside a string |
| `text` | a string the subset does not allow |
| `number` | a number that is not a finite JSON number |
| `key` | a key repeated in one object |
| `handle` | a handle declared twice |
| `reference` | an identifier value that names no earlier statement |
| `syntax` | anything else the grammar does not allow |
| `app_count` | no `lingara.app` statement (at 1:1), or more than one (at the second one's `const`) |
| `option` | an app option other than `client` and `manifest` |
| `manifest_key` | a manifest key outside the map (at the key), or a required one missing (at the object's `{`) |

A position is the first character of the offending token.

## Fixtures

[`connection-fixtures/`](connection-fixtures/) holds two directories:

- `valid/<slug>.js` with `valid/<slug>.json`, the wire manifest it converts
  to, or no `.json` when the file has no app statement (only `apps update`'s
  `app_count` would then refuse it). `valid/empty.js` is vendored from
  Generator's `web/src/features/builder/connection/fixtures/valid/`, the one
  script-free file there; `SOURCE` names where it came from.
- `refused/<slug>.js` with `refused/<slug>.json`: `{"code", "line",
  "column"}`, or `{"code": "connection_scripted", "statement": "<handle>"}`.
