// Every path this kit sends, spelled once and keyed by
// `conformance/routes.toml`'s logical names (ADR 16.10.26ad D2). The mock
// matches by path, so a spelling that drifts from that file meets a `599`.

export interface Route {
  /** The logical name: what `unexpected response from {route}` names. */
  name: string
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  path: string
  base: 'api' | 'portal'
}

const OBJECTS = '/platform/objects'
const OBJECT = '/platform/objects/{id}'
const CANVAS = '/v1/canvases/{id}'
const VERSION = '/platform/objects/{id}/versions/{semver}'

const FIXED = {
  'oauth.authorize': ['GET', '/oauth/authorize', 'portal'],
  'oauth.device_authorization': ['POST', '/oauth/device_authorization', 'api'],
  'oauth.token': ['POST', '/oauth/token', 'api'],
  'oauth.revoke': ['POST', '/oauth/revoke', 'api'],
  'developer.me': ['GET', '/platform/developer', 'api'],
  'canvas.list': ['GET', '/v1/canvases', 'api'],
  'canvas.create': ['POST', '/v1/canvases', 'api'],
  'canvas.get': ['GET', CANVAS, 'api'],
  'canvas.rename': ['PATCH', CANVAS, 'api'],
  'canvas.put_draft': ['PUT', '/v1/canvases/{id}/draft', 'api'],
  'canvas.dry_run': ['POST', '/v1/canvases/dry-run', 'api'],
  'canvas.activate': ['POST', '/v1/canvases/{id}/activate', 'api'],
  // `?kind=canvas&runtime_id=…` is the caller's query (D2).
  'canvas.lookup': ['GET', OBJECTS, 'api'],
  // The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
  'app_dev.get': ['GET', '/platform/objects/{id}/dev', 'api'],
  'app_dev.patch': ['PATCH', '/platform/objects/{id}/dev', 'api'],
  'app_dev.session_open': ['POST', '/platform/objects/{id}/dev/session', 'api'],
  'app_dev.session_renew': ['POST', '/platform/objects/{id}/dev/session/{session_id}/renew', 'api'],
  'app_dev.session_end': ['DELETE', '/platform/objects/{id}/dev/session/{session_id}', 'api'],
} as const

export type FixedName = keyof typeof FIXED

export function fixed(name: FixedName): Route {
  const [method, path, base] = FIXED[name]
  return { name, method, path, base }
}

/** One operation of the `/platform/objects` family (16.10.26v §8). */
const OBJECT_OPS = {
  list: ['GET', OBJECTS],
  get: ['GET', OBJECT],
  create: ['POST', OBJECTS],
  rename: ['PATCH', OBJECT],
  version_get: ['GET', VERSION],
  version_put: ['PUT', VERSION],
  version_create: ['POST', '/platform/objects/{id}/versions'],
  publish: ['POST', `${VERSION}/publish`],
  versions: ['GET', '/platform/objects/{id}/versions'],
  submit: ['POST', `${VERSION}/submit`],
  withdraw: ['POST', `${VERSION}/withdraw`],
  delete: ['DELETE', OBJECT],
  delete_version: ['DELETE', VERSION],
  secret_create: ['POST', '/platform/objects/{id}/secrets'],
  secret_delete: ['DELETE', '/platform/objects/{id}/secrets/{secret_id}'],
  enable: ['POST', '/platform/objects/{id}/enable'],
  disable: ['POST', '/platform/objects/{id}/disable'],
  test: ['POST', '/platform/objects/{id}/test'],
} as const

export type Op = keyof typeof OBJECT_OPS

/** `<word>.<op>`: `object('webhook_outbound', 'get')` is `GET /platform/objects/{id}`. */
export function object(word: string, op: Op): Route {
  return objectNamed(`${word}.${op}`, op)
}

/** The same path under another name: `canvas.object_get` is `<word>.get`'s path. */
export function objectNamed(name: string, op: Op): Route {
  const [method, path] = OBJECT_OPS[op]
  return { name, method, path, base: 'api' }
}

export const isTokenRoute = (r: Route): boolean => r.name === 'oauth.token' || r.name === 'oauth.device_authorization'
export const isPlatformRoute = (r: Route): boolean => r.path.startsWith('/platform/')
