// What the CLI checks in a wire manifest before it sends one (ADR 16.10.26ae
// D6): the ephemeral render URL a version may never carry, the manifest rules
// a warning depends on, and the warnings themselves, pinned by
// `spec/manifest-warnings.json` (the app kits' `manifestWarnings`). The
// server stays the authority on every rule; these run first so a refusal
// costs no request.

type Json = Record<string, unknown>

/** The slots a manifest may name (the app view's `AppSlotName`). */
const SLOTS = ['home.side', 'plans.empty_detail']

const WARNING_TEXTS: Record<string, string> = {
  single_locale: 'the listing is in its default locale only; give name and description a second locale',
}

/** The URL's host, lowercased: no scheme, user, port or path. */
function host(url: string): string {
  const rest = url.includes('://') ? url.slice(url.indexOf('://') + 3) : url
  const authority = rest.split(/[/?#]/)[0]
  return (authority.split('@').pop() ?? '').split(':')[0].toLowerCase()
}

/** A quick tunnel's or the template's placeholder: never a version's URL. */
export function ephemeral(renderUrl: string): boolean {
  const h = host(renderUrl)
  return h === 'dev.invalid' || h === 'trycloudflare.com' || h.endsWith('.trycloudflare.com')
}

const isObject = (v: unknown): v is Json => typeof v === 'object' && v !== null && !Array.isArray(v)
const localeMap = (v: unknown): boolean => isObject(v) && Object.keys(v).length > 0 && Object.values(v).every((s) => typeof s === 'string' && s !== '')
const hasDuplicate = (v: unknown): boolean => Array.isArray(v) && new Set(v.map((x) => JSON.stringify(x))).size !== v.length

/** The first rule `m` breaks, of the ones a warning depends on, in the app kits' order. */
export function brokenRule(m: Json): string | undefined {
  const locale = typeof m.default_locale === 'string' ? m.default_locale : ''
  const inMap = (key: string): boolean => !isObject(m[key]) || Object.hasOwn(m[key] as Json, locale)
  const url = m.render_url
  const checks: [string, () => boolean][] = [
    ['manifest_version', () => m.manifest_version === 1],
    ['default_locale', () => locale !== '' && inMap('name') && inMap('description')],
    ['name', () => localeMap(m.name)],
    ['description', () => localeMap(m.description)],
    ['render_url', () => typeof url === 'string' && /^https:\/\//i.test(url) && host(url) !== ''],
    ['slots', () => Array.isArray(m.slots) && m.slots.length > 0 && m.slots.every((s) => SLOTS.includes(s as string))],
    ['context', () => m.context === undefined || (Array.isArray(m.context) && m.context.every((c) => typeof c === 'string'))],
    ['locales', () => !('locales' in m)],
    ['duplicate', () => !['slots', 'context', 'scopes'].some((k) => hasDuplicate(m[k]))],
  ]
  return checks.find(([, kept]) => !kept())?.[0]
}

const keysOf = (v: unknown): string[] => (isObject(v) ? Object.keys(v) : [])

function compareBytes(a: string, b: string): number {
  const [x, y] = [Buffer.from(a), Buffer.from(b)]
  return Buffer.compare(x, y)
}

/** Where `name` and `description` both have an entry, and `long_description` too when present and not null, in byte order. */
export function locales(m: Json): string[] {
  if (brokenRule(m) !== undefined) return []
  const long = m.long_description
  const covered = (l: string): boolean => keysOf(m.description).includes(l) && (long === undefined || long === null || keysOf(long).includes(l))
  return keysOf(m.name).filter(covered).sort(compareBytes)
}

/** The listing's warnings: v1 `single_locale` alone. Never a refusal. */
export function warnings(m: Json): string[] {
  const ls = locales(m)
  return ls.length === 1 && ls[0] === m.default_locale ? ['single_locale'] : []
}

export const warningText = (code: string): string => WARNING_TEXTS[code] ?? ''
