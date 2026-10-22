// Every path this kit sends, spelled once and keyed by
// `conformance/routes.toml`'s logical names (ADR 16.10.26ad D2). The mock
// matches by path, so a spelling that drifts from that file meets a `599`
// and fails conformance. The tables are indexed by their enum, so a route is
// data, never a branch.
package com.getlingara.cli.kotlin.http

/** Which base a path is under: the API, or the portal's browser pages. */
enum class Base { API, PORTAL }

data class Route(val name: String, val method: String, val path: String, val base: Base = Base.API) {
    /** `oauth.token` and `oauth.device_authorization`: no bearer, and their RFC envelope is B2 rule 3's. */
    val isToken: Boolean get() = name == "oauth.token" || name == "oauth.device_authorization"

    val isPlatform: Boolean get() = path.startsWith("/platform/")
}

private const val OBJECTS = "/platform/objects"
private const val OBJECT = "/platform/objects/{id}"
private const val CANVAS = "/v1/canvases/{id}"
private const val VERSION = "/platform/objects/{id}/versions/{semver}"
private const val DEV = "/platform/objects/{id}/dev"
private const val DEV_SESSION = "/platform/objects/{id}/dev/session"

/** The routes that are not a kind's `/platform/objects` family. */
enum class Fixed(val route: Route) {
    AUTHORIZE(Route("oauth.authorize", "GET", "/oauth/authorize", Base.PORTAL)),
    DEVICE_AUTHORIZATION(Route("oauth.device_authorization", "POST", "/oauth/device_authorization")),
    TOKEN(Route("oauth.token", "POST", "/oauth/token")),
    REVOKE(Route("oauth.revoke", "POST", "/oauth/revoke")),
    DEVELOPER_ME(Route("developer.me", "GET", "/platform/developer")),
    CANVAS_LIST(Route("canvas.list", "GET", "/v1/canvases")),
    CANVAS_CREATE(Route("canvas.create", "POST", "/v1/canvases")),
    CANVAS_GET(Route("canvas.get", "GET", CANVAS)),
    CANVAS_RENAME(Route("canvas.rename", "PATCH", CANVAS)),
    CANVAS_PUT_DRAFT(Route("canvas.put_draft", "PUT", "/v1/canvases/{id}/draft")),
    CANVAS_DRY_RUN(Route("canvas.dry_run", "POST", "/v1/canvases/dry-run")),
    CANVAS_ACTIVATE(Route("canvas.activate", "POST", "/v1/canvases/{id}/activate")),

    // `?kind=canvas&runtime_id=…` is the caller's query (D2).
    CANVAS_LOOKUP(Route("canvas.lookup", "GET", OBJECTS)),

    // The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
    APP_DEV_GET(Route("app_dev.get", "GET", DEV)),
    APP_DEV_PATCH(Route("app_dev.patch", "PATCH", DEV)),
    APP_DEV_SESSION_OPEN(Route("app_dev.session_open", "POST", DEV_SESSION)),
    APP_DEV_SESSION_RENEW(Route("app_dev.session_renew", "POST", "$DEV_SESSION/{session_id}/renew")),
    APP_DEV_SESSION_END(Route("app_dev.session_end", "DELETE", "$DEV_SESSION/{session_id}")),
}

/** One operation of the `/platform/objects` family (16.10.26v §8): its suffix, method and path. */
enum class Op(val suffix: String, val method: String, val path: String) {
    LIST("list", "GET", OBJECTS),
    GET("get", "GET", OBJECT),
    CREATE("create", "POST", OBJECTS),
    RENAME("rename", "PATCH", OBJECT),
    VERSION_GET("version_get", "GET", VERSION),
    VERSION_PUT("version_put", "PUT", VERSION),
    VERSION_CREATE("version_create", "POST", "/platform/objects/{id}/versions"),
    PUBLISH("publish", "POST", "$VERSION/publish"),
    VERSIONS("versions", "GET", "/platform/objects/{id}/versions"),
    SUBMIT("submit", "POST", "$VERSION/submit"),
    WITHDRAW("withdraw", "POST", "$VERSION/withdraw"),
    DELETE("delete", "DELETE", OBJECT),
    DELETE_VERSION("delete_version", "DELETE", VERSION),
    SECRET_CREATE("secret_create", "POST", "/platform/objects/{id}/secrets"),
    SECRET_DELETE("secret_delete", "DELETE", "/platform/objects/{id}/secrets/{secret_id}"),
    ENABLE("enable", "POST", "/platform/objects/{id}/enable"),
    DISABLE("disable", "POST", "/platform/objects/{id}/disable"),
    TEST("test", "POST", "/platform/objects/{id}/test"),
}

object Routes {
    /** `<word>.<op>`: `object("webhook_outbound", Op.GET)` is `webhook_outbound.get`, `GET /platform/objects/{id}`. */
    fun objectRoute(word: String, op: Op): Route = Route("$word.${op.suffix}", op.method, op.path)

    /** The same path under another name: `canvas.object_get` is `<word>.get`'s path, renamed because `canvas.get` is the `/v1` read. */
    fun named(name: String, op: Op): Route = Route(name, op.method, op.path)
}
