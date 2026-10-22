// cloudflared (ADR 16.10.26ae D8). **Lookup**: `LINGARA_CLOUDFLARED` and
// then only that path, else `cloudflared` on `PATH`, refused below
// `tools.toml`'s minimum. **URL**: the CLI picks a free loopback port for
// `--metrics` and polls its `/quicktunnel`, falling back to the
// `https://…trycloudflare.com` line on stderr. **Ready**: the probe URL
// (`LINGARA_TUNNEL_PROBE_URL`, default `https://{hostname}/`) answers anything
// but Cloudflare's `530`. Each wait is 30 s.
import { spawnSync } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'
import { createServer } from 'node:net'
import { delimiter, join } from 'node:path'
import { createInterface } from 'node:readline'

import { cloudflaredMinVersion } from '../lib/assets.js'
import type { Env } from '../lib/env.js'
import { Exit, Fail, fromMessage } from '../lib/fail.js'
import { Group, sleep } from '../supervise.js'

export const WAIT_MS = 30_000
const POLL_MS = 250
const KEPT_LINES = 20
const DEFAULT_PROBE = 'https://{hostname}/'

/** `tool_missing` with D8's hint, the exit row's own message. */
export const missing = (): Fail => fromMessage(Exit.toolMissing, 'tool_missing_cloudflared')

const isFile = (p: string): boolean => existsSync(p) && statSync(p).isFile()

function onPath(env: Env): string | undefined {
  const exe = process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'
  return (env.get('PATH') ?? '').split(delimiter).map((d) => join(d, exe)).find(isFile)
}

/** The first `a.b.c` on `cloudflared --version`'s first line. */
function versionOf(binary: string): number[] | undefined {
  const out = spawnSync(binary, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  const first = (out.stdout ?? '').split('\n')[0]
  const word = first.split(/\s+/).find((w) => /^\d+(\.\d+)+$/.test(w))
  return word?.split('.').map(Number)
}

const atLeast = (v: number[], min: number[]): boolean => {
  for (let i = 0; i < Math.max(v.length, min.length); i += 1) {
    if ((v[i] ?? 0) !== (min[i] ?? 0)) return (v[i] ?? 0) > (min[i] ?? 0)
  }
  return true
}

/** Lookup and version check, offline (D7 step 2). */
export function find(env: Env): string {
  const explicit = env.get('LINGARA_CLOUDFLARED')
  const binary = explicit !== undefined ? (isFile(explicit) ? explicit : undefined) : onPath(env)
  if (binary === undefined) throw missing()
  const version = versionOf(binary)
  if (version === undefined || !atLeast(version, cloudflaredMinVersion())) throw missing()
  return binary
}

/** A free loopback port, for cloudflared's metrics server. */
const freePort = (): Promise<number> =>
  new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => resolve(typeof address === 'object' && address !== null ? address.port : 0))
    })
  })

/** The `https://…trycloudflare.com` hostname on one log line, if any. */
export function hostnameIn(line: string): string | undefined {
  const m = /https:\/\/([A-Za-z0-9.-]+)/.exec(line)
  return m !== null && m[1].endsWith('.trycloudflare.com') ? m[1] : undefined
}

const get = async (url: string): Promise<Response | undefined> => {
  try {
    return await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(2000) })
  } catch {
    return undefined
  }
}

/** One cloudflared incarnation. */
export class Tunnel {
  lines: string[] = []
  loggedHost?: string

  private constructor(
    public group: Group,
    public metrics: string,
  ) {
    const stderr = group.child.stderr
    if (stderr !== null) createInterface({ input: stderr }).on('line', (line) => this.keep(line))
  }

  /** `cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>`. */
  static async spawn(binary: string, port: number): Promise<Tunnel> {
    const metrics = `127.0.0.1:${await freePort()}`
    const args = ['tunnel', '--url', `http://127.0.0.1:${port}`, '--metrics', metrics]
    return new Tunnel(Group.spawn(binary, args, { stdio: ['ignore', 'ignore', 'pipe'] }), metrics)
  }

  private keep(line: string): void {
    this.loggedHost ??= hostnameIn(line)
    this.lines.push(line)
    if (this.lines.length > KEPT_LINES) this.lines.shift()
  }

  get alive(): boolean {
    return !this.group.done
  }

  /** The last lines cloudflared wrote, for `tunnel_failed`'s reason. */
  tail(): string {
    return this.lines.join('\n')
  }

  private async quicktunnel(): Promise<string | undefined> {
    const answer = await get(`http://${this.metrics}/quicktunnel`)
    const body = answer === undefined ? undefined : ((await answer.json().catch(() => undefined)) as { hostname?: unknown } | undefined)
    return typeof body?.hostname === 'string' && body.hostname !== '' ? body.hostname : undefined
  }

  /** The quick tunnel's hostname: metrics first, the log line second. */
  async hostname(stop: () => boolean): Promise<string | undefined> {
    const deadline = Date.now() + WAIT_MS
    while (Date.now() < deadline && this.alive && !stop()) {
      const host = (await this.quicktunnel()) ?? this.loggedHost
      if (host !== undefined) return host
      await sleep(POLL_MS)
    }
    return undefined
  }

  /** Whether the probe reached the app through the tunnel: any answer but a `530`, once a second, for at most 30 s. */
  async ready(host: string, template: string | undefined, stop: () => boolean): Promise<boolean> {
    const url = (template ?? DEFAULT_PROBE).split('{hostname}').join(host).split('{metrics}').join(this.metrics)
    const deadline = Date.now() + WAIT_MS
    while (Date.now() < deadline && this.alive && !stop()) {
      const answer = await get(url)
      if (answer !== undefined && answer.status !== 530) return true
      await sleep(1000)
    }
    return false
  }

  stop(): Promise<void> {
    return this.group.stop(5000)
  }
}
