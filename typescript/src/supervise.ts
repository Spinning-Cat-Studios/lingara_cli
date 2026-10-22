// The children `apps dev` runs (ADR 16.10.26ae D5, D7 steps 4 and 8): the
// app's environment, built from the parent's with the CLI's own platform
// credentials scrubbed; the platform shell (`sh -c`, `cmd /C`); a process
// group per child (`detached`), so a terminal's Ctrl+C reaches only the CLI;
// and a stop that signals the whole group, SIGTERM then SIGKILL after a
// grace period (the process tree on Windows).
import { type ChildProcess, spawn, spawnSync, type SpawnOptions } from 'node:child_process'
import { connect } from 'node:net'
import { createInterface } from 'node:readline'
import type { Readable } from 'node:stream'

/** The CLI's own credentials, never the app's (D5): `LINGARA_PLATFORM_*`. */
export const SCRUBBED_PREFIX = 'LINGARA_PLATFORM_'

export const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

/** `[command, args]` that run `line` through the platform shell. */
export const shellArgs = (line: string): [string, string[]] => (process.platform === 'win32' ? ['cmd', ['/C', line]] : ['sh', ['-c', line]])

/** The parent's environment, minus the CLI's credentials, plus `extra` (later entries win). */
export function childEnv(parent: Record<string, string>, extra: [string, string][]): Record<string, string> {
  const env = Object.fromEntries(Object.entries(parent).filter(([k]) => !k.startsWith(SCRUBBED_PREFIX)))
  for (const [k, v] of extra) env[k] = v
  return env
}

/** A running child that leads its own process group. */
export class Group {
  status?: number | null
  signal?: NodeJS.Signals | null
  private exited: Promise<void>

  constructor(public child: ChildProcess) {
    this.exited = new Promise((resolve) => {
      child.on('exit', (code, signal) => {
        this.status = code
        this.signal = signal
        resolve()
      })
      child.on('error', () => {
        this.status = this.status ?? 127
        resolve()
      })
    })
  }

  static spawn(command: string, args: string[], options: SpawnOptions): Group {
    return new Group(spawn(command, args, { ...options, detached: true }))
  }

  get done(): boolean {
    return this.status !== undefined
  }

  private signalGroup(signal: NodeJS.Signals): void {
    const pid = this.child.pid
    if (pid === undefined) return
    try {
      process.kill(-pid, signal)
    } catch {
      // The group is already gone.
    }
  }

  /** Signals the whole group: SIGTERM, then SIGKILL once `graceMs` passes. */
  async stop(graceMs: number): Promise<void> {
    if (process.platform === 'win32') {
      if (this.child.pid !== undefined) spawnSync('taskkill', ['/T', '/F', '/PID', String(this.child.pid)], { stdio: 'ignore' })
      await this.exited
      return
    }
    this.signalGroup('SIGTERM')
    await Promise.race([this.exited, sleep(graceMs)])
    // The group may outlive its leader: kill whatever is left of it.
    this.signalGroup('SIGKILL')
    await Promise.race([this.exited, sleep(1000)])
  }
}

/** Copies `source` to stderr line by line, each line after `prefix`. */
export function relay(source: Readable | null, prefix: string): void {
  if (source === null) return
  createInterface({ input: source }).on('line', (line) => process.stderr.write(`${prefix}${line}\n`))
}

const accepts = (port: number): Promise<boolean> =>
  new Promise((resolve) => {
    const socket = connect({ host: '127.0.0.1', port })
    const done = (ok: boolean): void => {
      socket.destroy()
      resolve(ok)
    }
    socket.setTimeout(250, () => done(false))
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
  })

/** Whether `127.0.0.1:port` accepts a connection before `timeoutMs`, giving up early when `stop` says so. */
export async function waitListening(port: number, timeoutMs: number, stop: () => boolean): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await accepts(port)) return true
    if (stop()) return false
    await sleep(100)
  }
  return false
}
