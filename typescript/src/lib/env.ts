// The process environment, read once, and the config dir and `config.toml`
// (ADR 16.10.26ad D4). The dir is `LINGARA_CONFIG_DIR`, else
// `$XDG_CONFIG_HOME/lingara`, else `~/.config/lingara` on macOS and Linux
// alike, and `%APPDATA%\lingara` on Windows. `config.toml` holds only
// non-secret per-profile settings: `[profiles.<name>]` `api_url` and `output`.
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

export class Env {
  constructor(private vars: Record<string, string | undefined>) {}

  /** A set, non-empty variable. */
  get(name: string): string | undefined {
    const v = this.vars[name]
    return v === undefined || v === '' ? undefined : v
  }

  /** Every set variable: the environment `apps dev`'s child starts from. */
  all(): Record<string, string> {
    return Object.fromEntries(Object.entries(this.vars).filter((e): e is [string, string] => e[1] !== undefined))
  }
}

export function configDir(env: Env): string {
  const explicit = env.get('LINGARA_CONFIG_DIR')
  if (explicit !== undefined) return explicit
  if (process.platform === 'win32') return join(env.get('APPDATA') ?? homedir(), 'lingara')
  const xdg = env.get('XDG_CONFIG_HOME')
  if (xdg !== undefined) return join(xdg, 'lingara')
  return join(env.get('HOME') ?? homedir(), '.config', 'lingara')
}

export interface ProfileConfig {
  api_url?: string
  output?: string
}

/**
 * `[profiles.<name>]` from `config.toml`, read for its two string keys
 * only: a full TOML reader would be a dependency for two settings.
 */
export function profileConfig(dir: string, profile: string): ProfileConfig {
  let text: string
  try {
    text = readFileSync(join(dir, 'config.toml'), 'utf8')
  } catch {
    return {}
  }
  const out: ProfileConfig = {}
  let inProfile = false
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    const table = /^\[(.+)\]$/.exec(line)
    if (table !== null) inProfile = table[1].trim() === `profiles.${profile}` || table[1].trim() === `profiles."${profile}"`
    const pair = /^(api_url|output)\s*=\s*"([^"]*)"/.exec(line)
    if (inProfile && pair !== null) out[pair[1] as keyof ProfileConfig] = pair[2]
  }
  return out
}
