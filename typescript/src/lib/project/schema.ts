// `spec/platform-toml.schema.json`'s rules over the parsed file, written out
// (ADR 16.10.26ae D4): the kit carries no JSON Schema engine, and these are
// the same rules in the same order as the Rust kit's, so a file one kit
// refuses every kit refuses. Each returns the reason, or undefined.

type Table = Record<string, unknown>

const ROOT_KEYS = ['schema', 'platform', 'apps']
const PLATFORM_KEYS = ['api', 'profile']
const ROW_KEYS = ['name', 'id', 'path', 'port', 'start', 'render_path', 'manifest', 'manifest_command', 'credentials', 'env_file', 'env']
const CREDENTIALS = ['app_secret', 'client_credentials']
const NONEMPTY = ['id', 'path', 'start', 'manifest', 'manifest_command', 'env_file']

const isTable = (v: unknown): v is Table => typeof v === 'object' && v !== null && !Array.isArray(v) && !(v instanceof Date)

/** A lowercase letter, then up to 39 lowercase letters, digits or hyphens. */
export const appNameOk = (name: string): boolean => /^[a-z][a-z0-9-]{0,39}$/.test(name)

const closed = (table: Table, allowed: string[], at: string): string | undefined => {
  const stray = Object.keys(table).find((k) => !allowed.includes(k))
  return stray === undefined ? undefined : `${at}${stray} is not a key this file takes`
}

const nonempty = (table: Table, key: string, at: string): string | undefined => {
  const v = table[key]
  return v === undefined || (typeof v === 'string' && v !== '') ? undefined : `${at}${key} must be a non-empty string`
}

function checkPlatform(value: unknown): string | undefined {
  if (!isTable(value)) return '[platform] must be a table'
  const api = value.api
  const apiBad = api !== undefined && !(typeof api === 'string' && /^https?:\/\//.test(api))
  return closed(value, PLATFORM_KEYS, 'platform.') ?? (apiBad ? 'platform.api must be an http(s) URL' : undefined) ?? nonempty(value, 'profile', 'platform.')
}

function checkCredentials(row: Table, at: string): string | undefined {
  const value = row.credentials
  if (value === undefined) return undefined
  if (!Array.isArray(value)) return `${at}credentials must be a list`
  const ok = value.every((c, i) => typeof c === 'string' && CREDENTIALS.includes(c) && value.indexOf(c) === i)
  return ok ? undefined : `${at}credentials takes app_secret and client_credentials, each once`
}

function checkEnv(row: Table, at: string): string | undefined {
  const value = row.env
  if (value === undefined) return undefined
  if (!isTable(value)) return `${at}env must be a table`
  const bad = Object.entries(value).find(([, v]) => typeof v !== 'string')
  return bad === undefined ? undefined : `${at}env.${bad[0]} must be a string`
}

const portOk = (port: unknown): boolean => port === undefined || (Number.isInteger(port) && (port as number) >= 1 && (port as number) <= 65535)
const renderPathOk = (path: unknown): boolean => path === undefined || (typeof path === 'string' && path.startsWith('/'))

function checkFields(row: Table, at: string): string | undefined {
  const blank = NONEMPTY.map((key) => nonempty(row, key, at)).find((r) => r !== undefined)
  if (blank !== undefined) return blank
  if (!portOk(row.port)) return `${at}port must be a whole number from 1 to 65535`
  if (!renderPathOk(row.render_path)) return `${at}render_path must start with /`
  if ('manifest' in row && 'manifest_command' in row) return `${at}manifest and manifest_command are one or the other`
  return checkCredentials(row, at) ?? checkEnv(row, at)
}

function checkRow(value: unknown, at: string): string | undefined {
  if (!isTable(value)) return `${at} must be a table`
  const stray = closed(value, ROW_KEYS, at)
  if (stray !== undefined) return stray
  if (!(typeof value.name === 'string' && appNameOk(value.name))) return `${at}name must be a lowercase letter, then up to 39 lowercase letters, digits or hyphens`
  if (value.path === undefined) return `${at}path is missing`
  return checkFields(value, at)
}

function checkApps(apps: unknown): string | undefined {
  if (apps === undefined) return undefined
  if (!Array.isArray(apps)) return 'apps must be [[apps]] rows'
  return apps.map((row, i) => checkRow(row, `apps[${i}].`)).find((r) => r !== undefined)
}

function checkSchema(value: Table): string | undefined {
  if (value.schema === undefined) return 'schema = 1 is missing'
  return value.schema === 1 ? undefined : `schema is ${String(value.schema)}; this lingara reads 1`
}

export function check(value: unknown): string | undefined {
  if (!isTable(value)) return 'the file is not a table'
  const platform = value.platform === undefined ? undefined : checkPlatform(value.platform)
  return checkSchema(value) ?? closed(value, ROOT_KEYS, '') ?? platform ?? checkApps(value.apps)
}
