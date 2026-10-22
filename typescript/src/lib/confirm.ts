// The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant I1).
// One function, called by every dangerous command, so no command can carry a
// weaker copy.
//
// - `-y --confirm <email>` replaces both prompts, interactive or not, and
//   sends no read for a prompt; the email is checked before anything is
//   destroyed. Neither flag alone is confirmation.
// - Otherwise a non-interactive run is refused **before any request**, the
//   read and any token exchange included.
// - Interactive: the command's read fills the prompt, `y`/`yes` continues,
//   then the typed email must match. There is no retry loop.
import { message } from './assets.js'
import { Exit, Fail } from './fail.js'

/** What the y/n question names: the object, its cascade, its pins. */
export interface Prompt {
  kind: string
  name: string
  id: string
  /** The kind's `consequence_*` line, or `consequence_version`. */
  consequence: string
  pinnedBy: number
}

export interface ConfirmFlags {
  yes: boolean
  confirm?: string
}

/** What the ritual needs from a run: the terminal, the stream and the email. */
export interface RitualPorts {
  interactive(): boolean
  readLine(): Promise<string | undefined>
  prompt(text: string): void
  email(): Promise<string>
}

/** Trimmed and ASCII-lowercased: the comparison's rule and the form the request carries. */
export const canonical = (email: string): string => email.trim().replace(/[A-Z]/g, (c) => c.toLowerCase())

/** `dev@example.com` is `d…@example.com`; a one-character local part is `…@example.com`. */
export function hint(email: string): string {
  const at = email.indexOf('@')
  const [local, domain] = at < 0 ? [email, ''] : [email.slice(0, at), email.slice(at + 1)]
  const first = [...local].length > 1 ? [...local][0] : ''
  return `${first}…@${domain}`
}

const notConfirmed = (id: string): Fail => new Fail(Exit.notConfirmed, id, message(id))

/** `confirm_yes_no`, the consequence, and `confirm_pinned_by` when pinned. */
function question(p: Prompt): string {
  const lines = [message('confirm_yes_no', { kind: p.kind, name: p.name, id: p.id }), p.consequence]
  if (p.pinnedBy > 0) lines.push(message('confirm_pinned_by', { count: String(p.pinnedBy) }))
  return lines.join('\n')
}

/** The ritual; the canonical email the destructive request then carries. */
export async function confirmDestructive(ports: RitualPorts, flags: ConfirmFlags, read: () => Promise<Prompt>): Promise<string> {
  if (flags.yes && flags.confirm !== undefined) {
    const email = canonical(await ports.email())
    if (canonical(flags.confirm) !== email) throw notConfirmed('confirm_email_mismatch')
    return email
  }
  if (!ports.interactive()) throw notConfirmed('confirm_non_tty_refused')
  const prompt = await read()
  ports.prompt(`${question(prompt)} [y/N] `)
  const answer = ((await ports.readLine()) ?? '').trim().toLowerCase()
  if (answer !== 'y' && answer !== 'yes') throw notConfirmed('confirm_declined')
  const email = canonical(await ports.email())
  ports.prompt(`${message('confirm_type_email', { hint: hint(email) })} `)
  if (canonical((await ports.readLine()) ?? '') !== email) throw notConfirmed('confirm_email_mismatch')
  return email
}
