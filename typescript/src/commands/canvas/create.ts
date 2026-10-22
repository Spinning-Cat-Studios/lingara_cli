// `canvas create` (D1): `createCanvas` answers the canvas itself, so there is no final read.
import { readFileSync } from 'node:fs'

import { Flags } from '@oclif/core'

import { LingaraCommand, opt, type Parsed, pathFlag } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { Exit, Fail, usage } from '../../lib/fail.js'
import { fixed } from '../../lib/routes.js'

export function definitionFile(path: string): unknown {
  try {
    return JSON.parse(readFileSync(path, 'utf8')) as unknown
  } catch (e) {
    throw usage(`${path}: ${(e as Error).message}`)
  }
}

/** A `/v1/canvases` 404 while the builder flag is off (16.10.26x D3), where no id could be wrong. */
export function builderHint(e: unknown): never {
  if (e instanceof Fail && e.exit === Exit.notFound) e.hint('the canvas builder may not be enabled for this account')
  throw e
}

export default class CanvasCreate extends LingaraCommand {
  static override summary = 'Create a canvas, from a definition file or empty.'
  static override flags = {
    name: Flags.string({ summary: "The canvas's name" }),
    file: pathFlag({ helpValue: 'definition.json', summary: 'The definition; absent, an empty one' }),
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    const file = opt(flags.file)
    const definition = file === undefined ? { schema: 2, nodes: [], edges: [] } : definitionFile(file)
    const name = opt(flags.name)
    const body = { json: { ...(name === undefined ? {} : { name }), definition } }
    ctx.out.value(await ctx.call({ route: fixed('canvas.create'), body }).catch(builderHint))
  }
}
