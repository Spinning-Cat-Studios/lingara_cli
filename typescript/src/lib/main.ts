// One run: resolve argv to a command, parse it with oclif's parser, pick the
// profile and the API, run it, and turn the result into an exit code. `run`
// is the one entry point, used by `bin/run.js` and, in-process, by canvas
// apply's inner verbs (D7 step 4), which share this app's signed-in session,
// so no second token exchange happens.
import { createInterface, type Interface } from 'node:readline'
import { fileURLToPath } from 'node:url'

import { Config, Help, Parser } from '@oclif/core'

import { ENDPOINTS, VERSION } from './assets.js'
import type { LingaraCommand, Parsed } from './base.js'
import { type App, Ctx, type Terminal } from './ctx.js'
import { configDir, Env, profileConfig } from './env.js'
import { Fail, failure, usage } from './fail.js'
import { fetchTransport, Http, systemClock } from './http.js'
import { errorLines, Output, type Sink } from './output.js'
import { FileStore, KeychainStore, modeOf, Stores } from './store.js'

export interface Outcome {
  exit: number
  stdout: string
  fail?: Fail
}

let loaded: Promise<Config> | undefined

/** oclif's view of the tree, loaded once a process. */
export function loadConfig(): Promise<Config> {
  loaded ??= Config.load({ root: fileURLToPath(new URL('../..', import.meta.url)) })
  return loaded
}

/** `LC_ALL`, else `LC_MESSAGES`, else `LANG`, as BCP 47: `en_US.UTF-8` is `en-US`. */
function acceptLanguage(env: Env): string | undefined {
  const raw = env.get('LC_ALL') ?? env.get('LC_MESSAGES') ?? env.get('LANG')
  const tag = raw?.split(/[.@]/)[0].replace(/_/g, '-')
  return tag === undefined || tag === '' || tag === 'C' || tag === 'POSIX' ? undefined : tag
}

/** stdin, one line at a time, from one reader the run closes. */
class StdinTerminal implements Terminal {
  private reader?: Interface
  private lines: string[] = []
  private waiting: ((line: string | undefined) => void)[] = []

  interactive(): boolean {
    return process.stdin.isTTY === true && process.stderr.isTTY === true
  }

  readLine(): Promise<string | undefined> {
    if (this.reader === undefined) {
      this.reader = createInterface({ input: process.stdin, terminal: false })
      this.reader.on('line', (line) => (this.waiting.length > 0 ? this.waiting.shift()?.(line) : this.lines.push(line)))
      this.reader.on('close', () => this.waiting.splice(0).forEach((w) => w(undefined)))
    }
    const next = this.lines.shift()
    return next !== undefined ? Promise.resolve(next) : new Promise((resolve) => this.waiting.push(resolve))
  }

  close(): void {
    this.reader?.close()
  }
}

/** Where argv's leading words name a command (or one of its aliases), and the rest. */
function resolve(config: Config, argv: string[]): { id: string; rest: string[] } | undefined {
  const words: string[] = []
  for (const a of argv) {
    if (a.startsWith('-')) break
    words.push(a)
  }
  for (let n = words.length; n > 0; n -= 1) {
    const id = words.slice(0, n).join(':')
    if (config.findCommand(id) !== undefined) return { id, rest: argv.slice(n) }
  }
  return undefined
}

async function parse(cmd: typeof LingaraCommand, rest: string[]): Promise<Parsed> {
  try {
    const out = await Parser.parse(rest, { flags: { ...cmd.baseFlags, ...cmd.flags }, args: cmd.args, strict: true })
    return { flags: out.flags as Record<string, unknown>, args: out.args as Record<string, unknown> }
  } catch (e) {
    throw usage(String((e as Error).message).split('\n')[0])
  }
}

/** `--version` (the kit's own spelling, D10) and `--help` (oclif's), or nothing. */
async function early(config: Config, argv: string[], found: boolean): Promise<Outcome | undefined> {
  const help = argv.includes('--help') || argv.includes('-h')
  if (!help && (found || argv.length > 0)) return undefined
  await new Help(config).showHelp(argv.filter((a) => a !== '--help' && a !== '-h'))
  return { exit: 0, stdout: '' }
}

/**
 * The profile, and D3's API: `LINGARA_API_URL`, then `platform.toml`'s
 * `[platform] api`, then the profile's, then `endpoints.json`'s. The `apps`
 * commands that read a `platform.toml` apply its slot when they open the file
 * (16.10.26ae D4, `lib/dev/rows.ts`); everything else never reads one.
 */
function profileOf(app: App, input: Parsed): { profile: string; chosen: boolean; json: boolean } {
  const chosen = (input.flags.profile as string | undefined) ?? app.env.get('LINGARA_PROFILE')
  const profile = chosen ?? 'default'
  const settings = profileConfig(configDir(app.env), profile)
  app.http.bases.api = app.env.get('LINGARA_API_URL') ?? settings.api_url ?? app.endpoints.api_url
  return { profile, chosen: chosen !== undefined, json: input.flags.json === true || settings.output === 'json' }
}

async function dispatch(app: App, argv: string[], err: Sink): Promise<Outcome> {
  if (argv.length === 1 && (argv[0] === '--version' || argv[0] === '-V')) return { exit: 0, stdout: `lingara ${VERSION} (typescript)\n` }
  const config = await loadConfig()
  const found = resolve(config, argv)
  const shown = await early(config, argv, found !== undefined)
  if (shown !== undefined) return shown
  if (found === undefined) throw usage(`${argv.filter((a) => !a.startsWith('-')).join(' ') || argv.join(' ')} is not a lingara command`)
  const cmd = (await config.findCommand(found.id)?.load()) as unknown as typeof LingaraCommand
  const input = await parse(cmd, found.rest)
  const { profile, chosen, json } = profileOf(app, input)
  const out = new Output(err, json)
  try {
    await cmd.exec(new Ctx(app, out, profile, chosen), input)
    return { exit: 0, stdout: out.stdout }
  } catch (e) {
    return failed(e, out.json, err, out.stdout)
  }
}

function failed(e: unknown, json: boolean, err: Sink, stdout: string): Outcome {
  const fail = e instanceof Fail ? e : failure(e instanceof Error ? e.message : String(e))
  for (const line of errorLines(fail, json)) err.write(`${line}\n`)
  return { exit: fail.exit, stdout, fail }
}

export async function run(app: App, argv: string[], err: Sink): Promise<Outcome> {
  try {
    return await dispatch(app, argv, err)
  } catch (e) {
    return failed(e, argv.includes('--json'), err, '')
  }
}

function productionApp(): App & { terminal: StdinTerminal } {
  const env = new Env(process.env)
  const stores = new Stores(modeOf(env.get('LINGARA_KEYRING')), new KeychainStore(), new FileStore(configDir(env)))
  const portal = env.get('LINGARA_PLATFORM_URL') ?? ENDPOINTS.platform_url
  const http = new Http(fetchTransport, systemClock, { api: ENDPOINTS.api_url, portal }, userAgent(), acceptLanguage(env))
  const noBrowser = env.get('LINGARA_NO_BROWSER') === '1'
  const browser = async (url: string): Promise<boolean> => {
    if (noBrowser) return false
    const open = (await import('open')).default
    return open(url).then(() => true, () => false)
  }
  const app: App & { terminal: StdinTerminal } = { http, env, stores, terminal: new StdinTerminal(), browser, endpoints: ENDPOINTS, run: (argv, err) => run(app, argv, err) }
  return app
}

/** `lingara-cli/<semver> (typescript; <os>)`, contract B5. */
function userAgent(): string {
  const os = { darwin: 'macos', win32: 'windows' }[process.platform as string] ?? process.platform
  return `lingara-cli/${VERSION} (typescript; ${os.replace(/[^a-z0-9_]/g, '_')})`
}

export async function main(argv: string[]): Promise<number> {
  const app = productionApp()
  const outcome = await run(app, argv, process.stderr)
  app.terminal.close()
  await new Promise<void>((done) => process.stdout.write(outcome.stdout, () => done()))
  return outcome.exit
}
