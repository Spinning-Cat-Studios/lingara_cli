// The one line of advice a refusal's code earns (ADR 16.10.26ad D3, D5, D6).
// Keyed by code, so a reworded server sentence changes nothing.
import type { Fail } from '../fail.js'

export const PORTAL_LINE = 'create your developer account at https://platform.getlingara.com'

const BY_CODE: Record<string, string> = {
  developer_not_found: PORTAL_LINE,
  developer_account_required: PORTAL_LINE,
  developer_terms_outdated: 'accept the updated developer terms at https://platform.getlingara.com',
  delete_confirmation_mismatch: 'the platform holds a different email for you; run `lingara whoami`',
  invalid_scope: 'recreate this client with `lingara client create --ci`',
}

/** `fail` with its code's line, once. */
export function advise(fail: Fail): Fail {
  const line = BY_CODE[fail.code]
  return line === undefined ? fail : fail.hint(line)
}
