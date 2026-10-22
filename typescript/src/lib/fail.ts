// The exit codes (16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10, rows
// 9–18) and the one refusal every command throws, which `output.ts` prints as
// contract B2's line.
import { message } from './assets.js'

export const Exit = {
  ok: 0,
  failure: 1,
  usage: 2,
  authRequired: 3,
  notFound: 4,
  refused: 5,
  notConfirmed: 6,
  unavailable: 7,
  platformDisabled: 8,
  toolMissing: 9,
  tunnelFailed: 10,
  devSessionHeld: 11,
  devSessionLost: 12,
  appExited: 13,
  connectionScripted: 14,
  renderUrlEphemeral: 15,
  platformTomlSecret: 16,
  platformTomlSchema: 17,
  appNotListening: 18,
  interrupted: 130,
} as const

export type ExitCode = (typeof Exit)[keyof typeof Exit]

/** Each row's name, as `apps dev --json` reports how it ended. */
const NAMES: Record<number, string> = {
  0: 'ok', 1: 'failure', 2: 'usage', 3: 'auth_required', 4: 'not_found', 5: 'refused', 6: 'not_confirmed',
  7: 'unavailable', 8: 'platform_disabled', 9: 'tool_missing', 10: 'tunnel_failed', 11: 'dev_session_held',
  12: 'dev_session_lost', 13: 'app_exited', 14: 'connection_scripted', 15: 'render_url_ephemeral',
  16: 'platform_toml_secret', 17: 'platform_toml_schema', 18: 'app_not_listening', 130: 'interrupted',
}

export const exitName = (code: number): string => NAMES[code] ?? 'failure'

/** An object a create made before it failed, named on stderr (D2). */
export interface Made {
  kind: string
  id: string
}

export class Fail extends Error {
  hints: string[] = []
  made: Made[] = []
  /** The refusing answer's body, for a command that re-words it. */
  body?: string

  constructor(
    public exit: ExitCode,
    public code: string,
    message: string,
  ) {
    super(message)
  }

  hint(line: string): Fail {
    if (!this.hints.includes(line)) this.hints.push(line)
    return this
  }
}

export const usage = (message: string): Fail => new Fail(Exit.usage, 'usage', message)
export const authRequired = (message: string): Fail => new Fail(Exit.authRequired, 'auth_required', message)
export const failure = (message: string): Fail => new Fail(Exit.failure, 'failure', message)
export const refused = (code: string, message: string): Fail => new Fail(Exit.refused, code, message)

/** A refusal with a `messages.en.json` text: code and message id are one word, as every 16.10.26ae row's are. */
export const fromMessage = (exit: ExitCode, id: string, vars: Record<string, string> = {}): Fail => new Fail(exit, id, message(id, vars))
