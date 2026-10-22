// The closed key map from `lingara.v1`'s `ManifestOptions` to the wire
// manifest (spec/connection-literal.md), in `lingara.manifest`'s member
// order. A key the map does not name is refused, never passed through.
import { at, type Lit, type Member, type Pos, toJson } from './types.js'

/** Literal key → wire key, in the order the wire manifest writes them. */
const KEYS: [string, string][] = [
  ['defaultLocale', 'default_locale'],
  ['name', 'name'],
  ['description', 'description'],
  ['renderUrl', 'render_url'],
  ['slots', 'slots'],
  ['context', 'context'],
  ['scopes', 'scopes'],
  ['tutorNote', 'tutor_note'],
  ['longDescription', 'long_description'],
  ['developer', 'developer'],
  ['images', 'images'],
]
const REQUIRED = ['name', 'description', 'renderUrl', 'slots']
const DEVELOPER: [string, string][] = [['name', 'name'], ['url', 'url'], ['supportUrl', 'support_url']]
const IMAGE: [string, string][] = [['sha256', 'sha256'], ['role', 'role'], ['alt', 'alt']]
const LOCALE_MAPS = ['name', 'description', 'longDescription']

const find = (members: Member[], key: string): Lit | undefined => members.find((m) => m.key === key)?.value

/** A string is the default locale's; an object passes its tags through. */
const localeMap = (lit: Lit, locale: string): unknown => ('str' in lit ? { [locale]: lit.str } : toJson(lit))

/** An object whose keys must all be in `map`, renamed through it. */
function renamed(lit: Lit, map: [string, string][], locale: string): unknown {
  if (!('obj' in lit)) return toJson(lit)
  const stray = lit.obj.find((m) => !map.some(([k]) => k === m.key))
  if (stray !== undefined) throw at('manifest_key', stray.at)
  const out: Record<string, unknown> = {}
  for (const [from, to] of map) {
    const value = find(lit.obj, from)
    if (value !== undefined) out[to] = from === 'alt' ? localeMap(value, locale) : toJson(value)
  }
  return out
}

const images = (lit: Lit, locale: string): unknown => ('arr' in lit ? lit.arr.map((i) => renamed(i, IMAGE, locale)) : toJson(lit))

function valueOf(from: string, value: Lit, locale: string): unknown {
  if (LOCALE_MAPS.includes(from)) return localeMap(value, locale)
  if (from === 'developer') return renamed(value, DEVELOPER, locale)
  if (from === 'images') return images(value, locale)
  return toJson(value)
}

const DEFAULTS: Record<string, unknown> = { context: [], scopes: [], tutorNote: false }

/** The wire manifest for the `manifest` object at `pos`. */
export function convert(members: Member[], pos: Pos): Record<string, unknown> {
  const stray = members.find((m) => !KEYS.some(([k]) => k === m.key))
  if (stray !== undefined) throw at('manifest_key', stray.at)
  if (REQUIRED.some((k) => find(members, k) === undefined)) throw at('manifest_key', pos)
  const localeLit = find(members, 'defaultLocale')
  const locale = localeLit !== undefined && 'str' in localeLit ? localeLit.str : 'en'
  const out: Record<string, unknown> = { manifest_version: 1, default_locale: localeLit === undefined ? 'en' : toJson(localeLit) }
  for (const [from, to] of KEYS.slice(1)) {
    const value = find(members, from)
    if (value !== undefined) out[to] = valueOf(from, value, locale)
    else if (from in DEFAULTS) out[to] = DEFAULTS[from]
  }
  return out
}
