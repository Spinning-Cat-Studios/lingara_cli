// What an `apps dev` session needs, decided before the first request (ADR
// 16.10.26ae D7 steps 1 and 2).

/** Where the dev version will point: a cloudflared it spawns, or a URL. */
export type Target = { cloudflared: string } | { fixed: string }

export interface Plan {
  name: string
  id: string
  dir: string
  port: number
  start: string
  renderPath: string
  target: Target
  takeOver: boolean
  client: boolean
  env: [string, string][]
  writeEnv?: string
  showEnv: boolean
  /** `LINGARA_TUNNEL_PROBE_URL`: where readiness is probed, never what is pointed at. */
  probe?: string
}
