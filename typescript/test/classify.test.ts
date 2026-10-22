import { describe, expect, it } from 'vitest'

import { type Answer, classify, failFor } from '../src/lib/classify.js'
import { Exit } from '../src/lib/fail.js'
import { Script, fakeCtx } from './fakes.js'

function answer(route: string, status: number, body: string): Answer {
  const tokenRoute = route === 'oauth.token' || route === 'oauth.device_authorization'
  return { route, tokenRoute, platformRoute: !tokenRoute && !route.startsWith('canvas.'), bearer: !tokenRoute, status, body }
}

const verdict = (route: string, status: number, body: object | string = ''): ReturnType<typeof classify> =>
  classify(answer(route, status, typeof body === 'string' ? body : JSON.stringify(body)))

describe('16.10.26ad AC14 platform disabled is not not-found', () => {
  it('keys B4 on codes, never on text', () => {
    expect(verdict('app.list', 404, { code: 'platform_disabled', error: 'x' })).toBe(Exit.platformDisabled)
    expect(verdict('developer.me', 403, { code: 'platform_access_not_enabled', error: 'x' })).toBe(Exit.platformDisabled)
    for (const error of ['access_denied', 'invalid_grant', 'unauthorized_client']) {
      expect(verdict('oauth.token', 400, { error, error_description: 'anything at all', lingara_code: 'platform_access_not_enabled' })).toBe(Exit.platformDisabled)
    }
    expect(verdict('oauth.token', 400, { error: 'invalid_grant', error_description: "The platform isn't enabled for this account yet." })).toBe(Exit.authRequired)
    expect(verdict('oauth.token', 400, { error: 'invalid_grant', lingara_code: 'developer_account_required' })).toBe(Exit.authRequired)
  })

  it('maps coded and empty 404s, a 401, the token route and the rest as B2 orders them', () => {
    expect(verdict('app.get', 404, { code: 'object_not_found', error: 'x' })).toBe(Exit.notFound)
    expect(verdict('developer.me', 404, { code: 'developer_not_found', error: 'x' })).toBe(Exit.refused)
    expect(verdict('app.get', 404, { code: 'developer_not_found', error: 'x' })).toBe(Exit.notFound)
    expect(verdict('app.list', 404)).toBe(Exit.failure)
    expect(verdict('app.list', 401)).toBe('refresh')
    expect(verdict('oauth.token', 401, { error: 'invalid_client' })).toBe(Exit.authRequired)
    expect(verdict('oauth.token', 429, { error: 'slow' })).toBe(Exit.unavailable)
    expect(verdict('oauth.token', 503, { error: 'down' })).toBe(Exit.unavailable)
    expect(verdict('app.list', 405)).toBe(Exit.failure)
    expect(verdict('app.list', 404, '<html>')).toBe(Exit.failure)
    for (const status of [400, 403, 409, 422]) expect(verdict('app.create', status, { code: 'c', error: 'e' })).toBe(Exit.refused)
    expect(verdict('app.list', 429)).toBe(Exit.unavailable)
    expect(verdict('app.list', 503, 'down for maintenance')).toBe(Exit.unavailable)
  })

  it('prints the server code, the kit sentence where there is none, and the wait', () => {
    const notFound = failFor(answer('app.get', 404, '{"code":"object_not_found","error":"No such object."}'), Exit.notFound)
    expect([notFound.code, notFound.message]).toEqual(['object_not_found', 'No such object.'])
    expect(failFor(answer('app.list', 404, ''), Exit.failure).message).toContain('app.list')
    expect(failFor(answer('app.list', 404, '{"code":"platform_disabled","error":"raw"}'), Exit.platformDisabled).message).toBe("The Lingara platform isn't enabled for this account yet.")
    expect(failFor({ ...answer('app.create', 429, ''), retryAfter: 30 }, Exit.unavailable).message).toMatch(/\(retry after 30 s\)$/)
  })

  it('refreshes once after a 401, and a second 401 is auth_required', async () => {
    const script = new Script()
      .then('GET /platform/objects?kind=app', 401)
      .then('POST /oauth/token', 200, { access_token: 'lgr_at_new', refresh_token: 'lgr_rt_new' })
      .then('GET /platform/objects?kind=app', 401)
    const { ctx } = fakeCtx(script)
    const { list, APP } = await import('../src/lib/platform/objects.js')
    const fail = await list(ctx, APP).catch((e: { exit: number }) => e)
    expect((fail as { exit: number }).exit).toBe(Exit.authRequired)
    expect(script.sent().filter((s) => s.startsWith('POST /oauth/token'))).toHaveLength(1)
  })

  it('is unavailable on a connection reset', async () => {
    const { ctx } = fakeCtx(new Script())
    const { list, APP } = await import('../src/lib/platform/objects.js')
    const fail = await list(ctx, APP).catch((e: { exit: number }) => e)
    expect((fail as { exit: number }).exit).toBe(Exit.unavailable)
  })
})

describe('16.10.26ae D10 rule 4a', () => {
  it('makes a dev-session 409 its own row, keyed on route and body code', () => {
    const held = { code: 'dev_session_held', error: 'x', lease_expires_at: '2026-10-20T15:00:00Z' }
    expect(verdict('app_dev.session_open', 409, held)).toBe(Exit.devSessionHeld)
    expect(verdict('app_dev.patch', 409, held)).toBe(Exit.devSessionHeld)
    expect(verdict('app_dev.session_renew', 409, { code: 'dev_session_not_live', error: 'x' })).toBe(Exit.devSessionLost)
    expect(verdict('app_dev.session_renew', 409, { code: 'dev_session_expired', error: 'x' })).toBe(Exit.devSessionLost)
    expect(verdict('app.create', 409, held)).toBe(Exit.refused)
    expect(verdict('app_dev.patch', 409, { code: 'version_conflict', error: 'x' })).toBe(Exit.refused)
    const fail = failFor(answer('app_dev.session_open', 409, JSON.stringify(held)), Exit.devSessionHeld)
    expect(fail.body).toContain('lease_expires_at')
  })
})
