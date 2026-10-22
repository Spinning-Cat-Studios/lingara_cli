// `lingara __tree` (ADR 16.10.26ab D4, 16.10.26ad D10): the registered tree,
// read back from `config.commands` rather than written twice. oclif has no
// topic aliases, so each `oauth_client …` command declares its `client …`
// alias, and those are folded back here onto one aliased topic.
//
// oclif's flag types are coarser than D2's: `pathFlag` is `path`,
// `portFlag` is `int`, an option flag is `enum` with its `options`, a
// boolean is `bool`. Positional args are all strings in this tree, which
// `ARG_TYPES` says once. A command is dangerous exactly when it carries the
// confirm group, the schema's own rule (16.10.26ab D2.4).
import type { Command, Config } from '@oclif/core'

import { INT, PATH } from './base.js'

interface Node {
  name: string
  aliases: string[]
  commands: Record<string, unknown>[]
  topics: Node[]
}

/** The one table positional arg types come from; registration declares every arg with `Args.string`. */
const ARG_TYPES: Record<string, string> = {}

type Flag = { type: string; char?: string; required?: boolean; multiple?: boolean; options?: readonly string[]; [PATH]?: boolean; [INT]?: boolean }

function flagType(f: Flag): string {
  if (f.type === 'boolean') return 'bool'
  if (f[PATH] === true) return 'path'
  if (f[INT] === true) return 'int'
  if (f.options !== undefined) return 'enum'
  return 'string'
}

function param(name: string, f: Flag): Record<string, unknown> {
  const out: Record<string, unknown> = { name, type: flagType(f), required: f.required === true, repeatable: f.multiple === true }
  if (f.char !== undefined) out.short = f.char
  if (f.options !== undefined && f.type !== 'boolean') out.values = [...f.options]
  return out
}

function topicAt(root: Node, words: string[]): Node {
  let at = root
  for (const w of words) {
    let next = at.topics.find((t) => t.name === w)
    if (next === undefined) {
      next = { name: w, aliases: [], commands: [], topics: [] }
      at.topics.push(next)
    }
    at = next
  }
  return at
}

function commandNode(name: string, cmd: Command.Class): Record<string, unknown> {
  const flags = Object.entries((cmd.flags ?? {}) as Record<string, Flag>).map(([n, f]) => param(n, f))
  const args = Object.entries(cmd.args ?? {}).map(([n, a]) => ({ name: n, type: ARG_TYPES[n] ?? 'string', required: a.required === true, repeatable: false }))
  return { name, aliases: [], dangerous: 'confirm' in (cmd.flags ?? {}), args, flags }
}

/** The topic alias an alias id spells: where its words first differ from the id's. */
function foldAlias(root: Node, id: string, alias: string): void {
  const [w, a] = [id.split(':'), alias.split(':')]
  const i = w.findIndex((word, k) => word !== a[k])
  if (i < 0) return
  const topic = topicAt(root, w.slice(0, i + 1))
  if (!topic.aliases.includes(a[i])) topic.aliases.push(a[i])
}

export async function tree(config: Config): Promise<Record<string, unknown>> {
  const root: Node = { name: 'lingara', aliases: [], commands: [], topics: [] }
  const aliasIds = new Set(config.commands.flatMap((c) => (c.id === c.aliases?.[0] ? [] : (c.aliases ?? []))))
  for (const c of config.commands.filter((c) => !aliasIds.has(c.id) && !c.hidden)) {
    const words = c.id.split(':')
    const cmd = await c.load()
    topicAt(root, words.slice(0, -1)).commands.push(commandNode(words[words.length - 1], cmd))
    for (const alias of c.aliases ?? []) foldAlias(root, c.id, alias)
  }
  const base = (config.commands[0] === undefined ? {} : ((await config.commands[0].load()).baseFlags ?? {})) as Record<string, Flag>
  return { ...root, global_flags: Object.entries(base).map(([n, f]) => param(n, f)) }
}
