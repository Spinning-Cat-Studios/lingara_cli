// The one base command every command extends (ADR 16.10.26ad D10). It owns
// `--json` and `--profile`; credentials, error mapping and output are the
// kit's `main`'s, so no command maps errors by itself. A command is oclif's
// for its tree and its parse, and the kit's for what it does (`exec`).
import { Args, Command, Flags } from '@oclif/core'

import type { Ctx } from './ctx.js'

export interface Parsed {
  flags: Record<string, unknown>
  args: Record<string, unknown>
}

/** D2's `path`, which oclif's flag types cannot say: `__tree` reads this mark. */
export const PATH = Symbol.for('lingara.path')

export function pathFlag(opts: { summary: string; required?: boolean; helpValue: string; exclusive?: string[] }) {
  const flag = opts.required === true ? Flags.file({ ...opts, required: true }) : Flags.file(opts)
  return Object.assign(flag, { [PATH]: true })
}

/** D2's `int`, which oclif's option flags do not say either: `__tree` reads this mark. */
export const INT = Symbol.for('lingara.int')

export function portFlag(summary: string) {
  return Object.assign(Flags.integer({ min: 1, max: 65535, helpValue: 'n', summary }), { [INT]: true })
}

/** `--config`: which platform.toml, before `LINGARA_PLATFORM_TOML` (16.10.26ae D4). */
export const configFlag = {
  config: pathFlag({ helpValue: 'path', summary: 'The platform.toml to read; default the nearest at or above this directory' }),
}

/** The `confirm` group every dangerous command carries (D5). Neither flag reads the environment. */
export const confirmFlags = {
  yes: Flags.boolean({ char: 'y', summary: 'Answer the y/n question; needs --confirm too' }),
  confirm: Flags.string({ helpValue: 'email', summary: 'Your developer email, typed back; needs -y too' }),
}

export const idArg = { id: Args.string({ required: true }) }

/** Every object `delete`: the id, an optional version, the ritual. */
export const deleteFlags = {
  version: Flags.string({ helpValue: 'semver', summary: 'Delete only this version' }),
  ...confirmFlags,
}

export abstract class LingaraCommand extends Command {
  static override baseFlags = {
    json: Flags.boolean({ summary: 'Machine-readable output on stdout' }),
    profile: Flags.string({ helpValue: 'name', summary: 'The stored credentials to use' }),
  }

  /** What the command does, given the run's context and its parsed argv. */
  static async exec(_ctx: Ctx, _input: Parsed): Promise<void> {
    throw new Error('a lingara command overrides exec')
  }

  /** Under oclif's own runner, a command runs through the kit's `main` as well. */
  async run(): Promise<void> {
    const { main } = await import('./main.js')
    process.exitCode = await main([...this.id?.split(':') ?? [], ...this.argv])
  }
}

/** A string flag or arg, or `undefined`. */
export const opt = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)
export const text = (v: unknown): string => (typeof v === 'string' ? v : '')
export const many = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])

/** The ritual's flags and an object delete's input, from a parse. */
export const deleteInput = (input: Parsed) => ({
  id: text(input.args.id),
  version: opt(input.flags.version),
  confirm: { yes: input.flags.yes === true, confirm: opt(input.flags.confirm) },
})
