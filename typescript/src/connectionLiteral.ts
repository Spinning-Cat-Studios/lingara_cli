// The connection literal subset (ADR 16.10.26ae D2; spec/connection-
// literal.md): `lingara/connection.js` read without evaluating JavaScript.
// `lib/literal/lex.ts` makes the tokens, `parse.ts` the statements,
// `manifest.ts` turns the one `lingara.app` statement's `manifest` into the
// wire manifest.
import { Exit, Fail, fromMessage } from './lib/fail.js'
import { tokens } from './lib/literal/lex.js'
import { convert } from './lib/literal/manifest.js'
import { statements as parseStatements } from './lib/literal/parse.js'
import { at, Refusal, type Statement } from './lib/literal/types.js'

export { Refusal }

/** The file's first line, byte for byte. */
export const HEADER = '// lingara connection v1 (schema 2)'

const WHAT: Record<string, string> = {
  header: 'the first line must be `// lingara connection v1 (schema 2)`',
  comment: 'comments go inside a script; the builder rewrites everything outside them',
  text: 'write text in quotes, with only the escapes JSON knows, and no raw tab or line break',
  number: 'write finite numbers in decimal, as JSON does',
  key: 'write each key once',
  handle: 'this handle is already declared',
  reference: 'this names no statement declared above it',
  syntax: 'not part of the literal subset',
  app_count: 'the file needs exactly one lingara.app statement',
  option: 'an app takes only client and manifest',
  manifest_key: 'not a manifest key lingara reads, or a required one is missing',
}

/** As B2 prints it: `connection_scripted`, else `usage` with `<file>:<line>:<column>: <what>`. */
export function refusalFail(r: Refusal, file: string): Fail {
  if (r.statement !== undefined) return fromMessage(Exit.connectionScripted, 'connection_scripted', { file, statement: r.statement })
  const pos = r.pos ?? { line: 1, column: 1 }
  return new Fail(Exit.usage, 'connection_literal', `${file}:${pos.line}:${pos.column}: ${WHAT[r.code] ?? WHAT.syntax} (${r.code})`)
}

/** Every statement in `text`. */
export function statements(text: string): Statement[] {
  const newline = text.indexOf('\n')
  const header = (newline === -1 ? text : text.slice(0, newline)).replace(/\r$/, '')
  if (header !== HEADER) throw at('header', { line: 1, column: 1 })
  const body = newline === -1 ? '' : text.slice(newline + 1)
  const lines = Math.max(1, text.split('\n').length - (text.endsWith('\n') ? 1 : 0))
  return parseStatements(tokens(body, 2), { line: lines + 1, column: 1 })
}

/** The wire manifest of the file's one `lingara.app` statement. */
export function appManifest(text: string): Record<string, unknown> {
  const apps = statements(text).filter((s) => s.kind === 'app')
  if (apps.length === 0) throw at('app_count', { line: 1, column: 1 })
  if (apps.length > 1) throw at('app_count', apps[1].at)
  const app = apps[0]
  if (!('obj' in app.value)) throw at('syntax', app.at)
  const stray = app.value.obj.find((m) => m.key !== 'client' && m.key !== 'manifest')
  if (stray !== undefined) throw at('option', stray.at)
  const manifest = app.value.obj.find((m) => m.key === 'manifest')?.value
  if (manifest === undefined || !('obj' in manifest)) throw at('manifest_key', app.value.at)
  return convert(manifest.obj, manifest.at)
}
