// A field node's op body: the web's `specFromFields` then `specBody`
// (`testRun.ts`, `plan.ts`), over the node's saved fields. A create's client
// is always `billing_mode: allowance`; no body carries `enabled`,
// `api_version` or `client_type`. The app manifest is `lingara.v1`'s
// `manifest()` wire form, with the defaults it fills.
type Rec = Record<string, unknown>

function text(v: unknown, what: string): string {
  if (typeof v !== 'string') throw new TypeError(`${what} must be a string`)
  return v
}

function texts(v: unknown, what: string): string[] {
  if (!Array.isArray(v) || !v.every((x) => typeof x === 'string')) throw new TypeError(`${what} must be an array of strings`)
  return [...(v as string[])]
}

const optionalTexts = (v: unknown, what: string): string[] | undefined => (v === undefined || v === null ? undefined : texts(v, what))

/** A plain string is `{locale: s}`; a map must hold strings only. */
function localeText(v: unknown, locale: string, what: string): Rec {
  if (typeof v === 'string') return { [locale]: v }
  if (typeof v === 'object' && v !== null && !Array.isArray(v) && Object.values(v).every((x) => typeof x === 'string')) return { ...(v as Rec) }
  throw new TypeError(`${what} must be a string or an object of strings`)
}

export function clientBody(fields: Rec, create: boolean): Rec {
  const body: Rec = { name: text(fields.name, 'name'), scopes: texts(fields.scopes, 'scopes') }
  const uris = optionalTexts(fields.redirectUris, 'redirectUris')
  if (uris !== undefined) body.redirect_uris = uris
  if (create) body.billing_mode = 'allowance'
  return body
}

export const webhookBody = (fields: Rec): Rec => ({ url: text(fields.url, 'url'), event_types: texts(fields.events, 'events') })

export const appBody = (fields: Rec): Rec => ({ manifest: manifest(fields.manifest) })

/** `lingara.v1`'s `manifest()`, in the order it writes the members. */
export function manifest(value: unknown): Rec {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new TypeError('manifest must be an object')
  const o = value as Rec
  const locale = o.defaultLocale === undefined ? 'en' : text(o.defaultLocale, 'defaultLocale')
  return {
    manifest_version: 1,
    default_locale: locale,
    name: localeText(o.name, locale, 'name'),
    description: localeText(o.description, locale, 'description'),
    render_url: text(o.renderUrl, 'renderUrl'),
    slots: texts(o.slots, 'slots'),
    context: optionalTexts(o.context, 'context') ?? [],
    scopes: optionalTexts(o.scopes, 'scopes') ?? [],
    tutor_note: o.tutorNote === true,
    ...listing(o, locale),
  }
}

/** The listing's three members, each written only when its option is set. */
function listing(o: Rec, locale: string): Rec {
  const out: Rec = {}
  if (o.longDescription !== undefined) out.long_description = localeText(o.longDescription, locale, 'longDescription')
  if (typeof o.developer === 'object' && o.developer !== null) {
    const d = o.developer as Rec
    out.developer = { name: text(d.name, 'developer.name'), ...(d.url === undefined ? {} : { url: text(d.url, 'url') }), ...(d.supportUrl === undefined ? {} : { support_url: text(d.supportUrl, 'supportUrl') }) }
  }
  if (Array.isArray(o.images)) {
    out.images = (o.images as Rec[]).map((i) => ({ sha256: text(i.sha256, 'images[].sha256'), role: text(i.role, 'images[].role'), alt: localeText(i.alt, locale, 'images[].alt') }))
  }
  return out
}

/** The name an app op registers under: the manifest's in its default locale. */
export function appName(body: Rec, fallback: string): string {
  const m = (body.manifest ?? {}) as Rec
  const names = (m.name ?? {}) as Rec
  const named = names[typeof m.default_locale === 'string' ? m.default_locale : 'en'] ?? Object.values(names).find((v) => typeof v === 'string')
  return typeof named === 'string' ? named : fallback
}
