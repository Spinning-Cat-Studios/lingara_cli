// `lingara __tree` (hidden): the registered tree as JSON, for `make check-tree`.
import { LingaraCommand } from '../lib/base.js'
import type { Ctx } from '../lib/ctx.js'
import { loadConfig } from '../lib/main.js'
import { tree } from '../lib/tree.js'

export default class Tree extends LingaraCommand {
  static override hidden = true
  static override summary = 'The registered command tree as JSON.'

  static override async exec(ctx: Ctx): Promise<void> {
    ctx.out.jsonValue(await tree(await loadConfig()))
  }
}
