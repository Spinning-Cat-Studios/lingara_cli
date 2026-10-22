// `apps list` (ADR 16.10.26ad D1), and `--pinned`, which reads platform.toml instead (16.10.26ae D4).
import { Flags } from '@oclif/core'

import { configFlag, LingaraCommand, opt, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { APP, printList } from '../../lib/platform/objects.js'
import { listPinned } from './local.js'

export default class AppsList extends LingaraCommand {
  static override summary = 'List your apps, or the apps platform.toml pins.'
  static override flags = {
    pinned: Flags.boolean({ summary: "List platform.toml's rows instead, reading nothing from the platform" }),
    ...configFlag,
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    if (flags.pinned === true) return listPinned(ctx, opt(flags.config))
    await printList(ctx, APP, [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['SERVING', 'serving_version'], ['VISIBILITY', 'visibility']])
  }
}
