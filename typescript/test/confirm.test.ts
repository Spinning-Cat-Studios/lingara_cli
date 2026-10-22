import { describe, expect, it } from 'vitest'

import { canonical, confirmDestructive, type ConfirmFlags, hint, type Prompt, type RitualPorts } from '../src/lib/confirm.js'
import { Exit, Fail } from '../src/lib/fail.js'

const PROMPT: Prompt = { kind: 'OAuth client', name: 'My server', id: 'oc1', consequence: 'Its tokens stop.', pinnedBy: 2 }

/** Runs the ritual over a fake terminal; its result, how often the read and the email ran, and what was prompted. */
async function ritual(tty: boolean, lines: string[], flags: ConfirmFlags) {
  const state = { reads: 0, emails: 0, prompted: '' }
  const ports: RitualPorts = {
    interactive: () => tty,
    readLine: async () => lines.shift(),
    prompt: (text) => (state.prompted += text),
    email: async () => {
      state.emails += 1
      return 'dev@conformance.invalid'
    },
  }
  const read = async () => {
    state.reads += 1
    return PROMPT
  }
  const result = await confirmDestructive(ports, flags, read).catch((e: Fail) => e)
  return { result, ...state }
}

const code = (r: string | Fail): string | undefined => (r instanceof Fail ? r.code : undefined)

describe('16.10.26ad AC4 the ritual is y/n then the email', () => {
  it('proceeds only on y/yes and then a matching email, handing back the canonical form', async () => {
    const ok = await ritual(true, ['y', '  Dev@Conformance.INVALID '], { yes: false })
    expect(ok.result).toBe('dev@conformance.invalid')
    expect(ok.reads).toBe(1)
    expect(ok.prompted).toContain('Delete OAuth client My server (oc1)?\nIts tokens stop.\nObjects pinning it: 2. [y/N] ')
    expect(ok.prompted).toContain('Type your developer email (d…@conformance.invalid) to confirm: ')
    expect((await ritual(true, ['YES', 'dev@conformance.invalid'], { yes: false })).result).toBe('dev@conformance.invalid')
  })

  it('declines on n or empty input, and mismatches without a retry', async () => {
    for (const answer of ['n', '', 'yep']) {
      const declined = await ritual(true, [answer], { yes: false })
      expect(code(declined.result)).toBe('confirm_declined')
      expect((declined.result as Fail).exit).toBe(Exit.notConfirmed)
      expect(declined.prompted).not.toContain('Type your developer email')
    }
    const mismatch = await ritual(true, ['y', 'someone@else.invalid', 'dev@conformance.invalid'], { yes: false })
    expect(code(mismatch.result)).toBe('confirm_email_mismatch')
  })

  it('never prompts or reads with -y and a matching --confirm, in a pipe or a terminal', async () => {
    for (const tty of [false, true]) {
      const flagged = await ritual(tty, [], { yes: true, confirm: 'DEV@conformance.invalid' })
      expect([flagged.result, flagged.reads, flagged.prompted]).toEqual(['dev@conformance.invalid', 0, ''])
    }
    const wrong = await ritual(false, [], { yes: true, confirm: 'someone@else.invalid' })
    expect([code(wrong.result), wrong.reads]).toEqual(['confirm_email_mismatch', 0])
  })

  it('refuses one flag alone in a pipe before any call', async () => {
    for (const flags of [{ yes: true }, { yes: false, confirm: 'dev@conformance.invalid' }, { yes: false }]) {
      const refused = await ritual(false, [], flags)
      expect([code(refused.result), refused.reads, refused.emails]).toEqual(['confirm_non_tty_refused', 0, 0])
    }
  })

  it('hints one letter and the domain', () => {
    expect(hint('dev@example.com')).toBe('d…@example.com')
    expect(hint('d@example.com')).toBe('…@example.com')
    expect(canonical('  A@B.c\n')).toBe('a@b.c')
  })
})
