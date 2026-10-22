// Every object `delete` (ADR 16.10.26ad D5): the ritual over one
// `<word>.get` for the prompt, then the destructive request with the
// canonical email.
import { message } from '../assets.js'
import { confirmDestructive, type ConfirmFlags, type Prompt } from '../confirm.js'
import type { Ctx } from '../ctx.js'
import { destroy, get, type Kind, type Obj, str } from './objects.js'

/** The kind's `consequence_*` line, from the object's read. */
export type Consequence = (obj: Obj) => string

/** `{client}` is the read's `parent_id`, the client's platform object id, never the public `client_id`. */
export const parent = (obj: Obj): string => str(obj.parent_id) || '-'

export function promptFor(k: Kind, obj: Obj, id: string, version: string | undefined, consequence: Consequence): Prompt {
  return {
    kind: k.label,
    name: str(obj.name) || id,
    id,
    consequence: version === undefined ? consequence(obj) : message('consequence_version', { semver: version }),
    pinnedBy: typeof obj.pinned_by === 'number' ? obj.pinned_by : 0,
  }
}

export interface DeleteInput {
  id: string
  version?: string
  confirm: ConfirmFlags
}

export async function deleteObject(ctx: Ctx, k: Kind, input: DeleteInput, consequence: Consequence): Promise<void> {
  const email = await confirmDestructive(ctx, input.confirm, async () => promptFor(k, await get(ctx, k, input.id), input.id, input.version, consequence))
  await destroy(ctx, k, input.id, input.version, email)
  ctx.out.nothing()
}
