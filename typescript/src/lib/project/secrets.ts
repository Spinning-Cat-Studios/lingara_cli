// D4's `platform_toml_secret` (ADR 16.10.26ae): the first value or key, in
// file order, that looks like a secret. A value is one when it matches
// `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id (`lgr_cid_…`); a
// key is one when its `_`-separated words include `secret`, `token` or
// `password`, case-insensitively (`CLIENT_SECRET`, `api_token`; never
// `TOKENIZER`). The refusal names the key, never the value.

const SECRET_WORDS = ['secret', 'token', 'password']
const VALUE = /lgr_([a-z]+)_[A-Za-z0-9]{8,}/g

export function secretValue(s: string): boolean {
  return [...s.matchAll(VALUE)].some((m) => m[1] !== 'cid')
}

export const secretKey = (key: string): boolean => key.split('_').some((w) => SECRET_WORDS.includes(w.toLowerCase()))

function inTable(table: object, at: string): string | undefined {
  for (const [k, v] of Object.entries(table)) {
    const path = at === '' ? k : `${at}.${k}`
    const found = secretKey(k) ? path : firstSecret(v, path)
    if (found !== undefined) return found
  }
  return undefined
}

/** The dotted path of the first secret-shaped key or value, if any. */
export function firstSecret(value: unknown, at = ''): string | undefined {
  if (typeof value === 'string') return secretValue(value) ? at : undefined
  if (Array.isArray(value)) return value.map((item) => firstSecret(item, at)).find((f) => f !== undefined)
  return typeof value === 'object' && value !== null ? inTable(value, at) : undefined
}
