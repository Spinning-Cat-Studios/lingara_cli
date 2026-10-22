import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import { createClient } from '../src/commands/oauth_client/create.js'
import { Exit, Fail } from '../src/lib/fail.js'
import { APP, bodyUpdate, type Obj } from '../src/lib/platform/objects.js'
import { Script, fakeCtx } from './fakes.js'

const ID = '7d1e2f30-0000-4000-8000-000000000001'

const object = (versions: unknown[], visibility: string) => ({ id: ID, kind: 'app', name: 'Flashcards', visibility, versions, serving_version: '1.0.0' })
const manifest = (body: Obj): void => {
  body.manifest = { name: 'v2' }
}

describe('16.10.26ad AC18 updates respect the one draft and creates never lose a secret', () => {
  it('edits the open draft at its draft_version, keeping the rest of its body, then publishes it', async () => {
    const script = new Script()
      .then(`GET /platform/objects/${ID}`, 200, object([{ semver: '1.1.0', state: 'draft' }, { semver: '1.0.0', state: 'published' }], 'private'))
      .then(`GET /platform/objects/${ID}/versions/1.1.0`, 200, { semver: '1.1.0', draft_version: 4, body: { manifest: {}, icon_key: 'k' } })
      .then(`PUT /platform/objects/${ID}/versions/1.1.0`, 200, { semver: '1.1.0' })
      .then(`POST /platform/objects/${ID}/versions/1.1.0/publish`, 200, { version: { semver: '1.1.0' } })
    await bodyUpdate(fakeCtx(script, true).ctx, APP, ID, manifest)
    expect(script.body(2)).toEqual({ body: { manifest: { name: 'v2' }, icon_key: 'k' }, draft_version: 4 })
  })

  it('makes a new version from the serving one when none is editable, and leaves a public one a draft', async () => {
    const script = new Script()
      .then(`GET /platform/objects/${ID}`, 200, object([{ semver: '1.0.0', state: 'published' }], 'public'))
      .then(`GET /platform/objects/${ID}/versions/1.0.0`, 200, { semver: '1.0.0', body: { manifest: {} } })
      .then(`POST /platform/objects/${ID}/versions`, 201, { semver: '1.1.0' })
    await bodyUpdate(fakeCtx(script, true).ctx, APP, ID, manifest)
    expect(script.body(2)).toEqual({ body: { manifest: { name: 'v2' } } })
    expect(script.sent()).toHaveLength(3)
  })

  it('reveals a minted secret and names the client when the final read fails', async () => {
    const script = new Script()
      .then('POST /platform/objects', 201, { id: 'oc1', visibility: 'private', version: { semver: '1.0.0' } })
      .then('POST /platform/objects/oc1/versions/1.0.0/publish', 200, {})
      .then('POST /platform/objects/oc1/secrets', 201, { secret_id: 's1', secret: 'lgr_cs_once' })
      .then('GET /platform/objects/oc1', 503)
    const { ctx } = fakeCtx(script, true)
    const args = { name: 'My server', scope: [], type: 'confidential', billingMode: 'allowance', redirectUri: [], ci: false }
    const fail = (await createClient(ctx, args).catch((e: Fail) => e)) as Fail
    expect([fail.exit, fail.made]).toEqual([Exit.unavailable, [{ kind: 'oauth_client', id: 'oc1' }]])
    expect(JSON.parse(ctx.out.stdout)).toEqual({ secret: { id: 's1', value: 'lgr_cs_once' } })
  })
})

describe('16.10.26ad AC19 --version names the kit', () => {
  it('prints lingara <VERSION> (typescript) and nothing else', async () => {
    const { run } = await import('../src/lib/main.js')
    const version = readFileSync(new URL('../../VERSION', import.meta.url), 'utf8').trim()
    const outcome = await run(fakeCtx(new Script()).ctx.app, ['--version'], { write: () => {} })
    expect([outcome.exit, outcome.stdout]).toEqual([0, `lingara ${version} (typescript)\n`])
  })
})
