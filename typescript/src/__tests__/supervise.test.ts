// The children's process groups and their stop (ADR 16.10.26ae AC24). The
// Rust kit's `supervise_tests.rs` carries the same test under the same name.
import { createInterface } from 'node:readline'

import { describe, expect, it } from 'vitest'

import { childEnv, Group, shellArgs } from '../supervise.js'

const alive = (pid: number): boolean => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

describe('supervise', () => {
  /**
   * 16.10.26ae AC24: `start` runs through the platform shell in its own
   * process group, so a signal to the CLI's group reaches neither child; the
   * stop signals the whole group, a grandchild included.
   */
  it.skipIf(process.platform === 'win32')('children_get_their_own_group_and_stop_in_order', async () => {
    const [shell, args] = shellArgs('sleep 30 & echo $!; wait')
    const group = Group.spawn(shell, args, { stdio: ['ignore', 'pipe', 'ignore'] })
    const grandchild = await new Promise<number>((resolve) => {
      createInterface({ input: group.child.stdout! }).once('line', (line) => resolve(Number(line.trim())))
    })
    const leader = group.child.pid as number
    // `detached` made it a group leader: signalling -pid reaches it.
    expect(() => process.kill(-leader, 0)).not.toThrow()
    const started = Date.now()
    await group.stop(5000)
    expect(Date.now() - started).toBeLessThan(5000)
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(alive(grandchild)).toBe(false)
  })

  it.runIf(process.platform === 'win32')('children_get_their_own_group_and_stop_in_order', async () => {
    const [shell, args] = shellArgs('ping -n 30 127.0.0.1 > NUL')
    const group = Group.spawn(shell, args, { stdio: 'ignore' })
    await group.stop(5000)
    expect(group.done).toBe(true)
  })

  it('the_child_never_inherits_the_cli_credentials', () => {
    const env = childEnv(
      { PATH: '/bin', LINGARA_PLATFORM_CLIENT_ID: 'lgr_cid_x', LINGARA_PLATFORM_CLIENT_SECRET: 'lgr_cs_x', LINGARA_APP_SECRET: 'stale' },
      [['LINGARA_APP_SECRET', 'fresh']],
    )
    expect(Object.keys(env).some((k) => k.startsWith('LINGARA_PLATFORM_'))).toBe(false)
    expect(env.LINGARA_APP_SECRET).toBe('fresh')
    expect(env.PATH).toBe('/bin')
  })
})
