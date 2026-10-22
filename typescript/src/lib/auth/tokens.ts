// A token response, and the profile record it becomes (ADR 16.10.26ad D4).
import type { ProfileRecord } from '../store.js'

/** The four scopes, in one grant (16.10.26u §3). */
export const SCOPES = 'platform:read platform:write canvas:read canvas:write'

export interface Tokens {
  access: string
  refresh?: string
  scope?: string
  expiresIn: number
}

const text = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)

/** RFC 6749 §5.1, read leniently: a missing `expires_in` is an hour. */
export function tokensOf(value: Record<string, unknown>): Tokens {
  const expires = value.expires_in
  return {
    access: text(value.access_token) ?? '',
    refresh: text(value.refresh_token),
    scope: text(value.scope),
    expiresIn: typeof expires === 'number' ? expires : 3600,
  }
}

/** `YYYY-MM-DDTHH:MM:SSZ`. */
export const rfc3339 = (secs: number): string => new Date(secs * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')

/** The refresh token rotates when one was sent, and `scopes` is rewritten whenever the answer carried `scope`. */
export function applyTokens(record: ProfileRecord, t: Tokens, now: number): void {
  record.access_token = t.access
  if (t.refresh !== undefined) record.refresh_token = t.refresh
  if (t.scope !== undefined) record.scopes = t.scope
  record.access_expires_at = rfc3339(now + t.expiresIn)
}
