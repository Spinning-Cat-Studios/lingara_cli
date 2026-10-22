package com.getlingara.cli.java.http;

/**
 * Every path this kit sends, spelled once and keyed by {@code conformance/routes.toml}'s logical
 * names (ADR 16.10.26ad D2). The mock matches by path, so a spelling that drifts from that file
 * meets a {@code 599} and fails conformance. Each route is an enum constant's data, never a
 * branch.
 */
public final class Routes {
    private static final String OBJECTS = "/platform/objects";
    private static final String OBJECT = "/platform/objects/{id}";
    private static final String VERSION = "/platform/objects/{id}/versions/{semver}";
    private static final String CANVAS = "/v1/canvases/{id}";
    private static final String DEV = "/platform/objects/{id}/dev";

    private Routes() {}

    /** The routes that are not a kind's {@code /platform/objects} family. */
    public enum Fixed {
        AUTHORIZE("oauth.authorize", "GET", "/oauth/authorize", true),
        DEVICE_AUTHORIZATION("oauth.device_authorization", "POST", "/oauth/device_authorization", false),
        TOKEN("oauth.token", "POST", "/oauth/token", false),
        REVOKE("oauth.revoke", "POST", "/oauth/revoke", false),
        DEVELOPER_ME("developer.me", "GET", "/platform/developer", false),
        CANVAS_LIST("canvas.list", "GET", "/v1/canvases", false),
        CANVAS_CREATE("canvas.create", "POST", "/v1/canvases", false),
        CANVAS_GET("canvas.get", "GET", CANVAS, false),
        CANVAS_RENAME("canvas.rename", "PATCH", CANVAS, false),
        CANVAS_PUT_DRAFT("canvas.put_draft", "PUT", "/v1/canvases/{id}/draft", false),
        CANVAS_DRY_RUN("canvas.dry_run", "POST", "/v1/canvases/dry-run", false),
        CANVAS_ACTIVATE("canvas.activate", "POST", "/v1/canvases/{id}/activate", false),
        // `?kind=canvas&runtime_id=…` is the caller's query (D2).
        CANVAS_LOOKUP("canvas.lookup", "GET", OBJECTS, false),
        // The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
        APP_DEV_GET("app_dev.get", "GET", DEV, false),
        APP_DEV_PATCH("app_dev.patch", "PATCH", DEV, false),
        APP_DEV_SESSION_OPEN("app_dev.session_open", "POST", DEV + "/session", false),
        APP_DEV_SESSION_RENEW("app_dev.session_renew", "POST", DEV + "/session/{session_id}/renew", false),
        APP_DEV_SESSION_END("app_dev.session_end", "DELETE", DEV + "/session/{session_id}", false);

        private final Route route;

        Fixed(String name, String method, String path, boolean portal) {
            this.route = new Route(name, method, path, portal);
        }

        public Route route() {
            return route;
        }
    }

    /** One operation of the {@code /platform/objects} family (16.10.26v §8). */
    public enum Op {
        LIST("list", "GET", OBJECTS),
        GET("get", "GET", OBJECT),
        CREATE("create", "POST", OBJECTS),
        RENAME("rename", "PATCH", OBJECT),
        VERSION_GET("version_get", "GET", VERSION),
        VERSION_PUT("version_put", "PUT", VERSION),
        VERSION_CREATE("version_create", "POST", "/platform/objects/{id}/versions"),
        PUBLISH("publish", "POST", VERSION + "/publish"),
        VERSIONS("versions", "GET", "/platform/objects/{id}/versions"),
        SUBMIT("submit", "POST", VERSION + "/submit"),
        WITHDRAW("withdraw", "POST", VERSION + "/withdraw"),
        DELETE("delete", "DELETE", OBJECT),
        DELETE_VERSION("delete_version", "DELETE", VERSION),
        SECRET_CREATE("secret_create", "POST", "/platform/objects/{id}/secrets"),
        SECRET_DELETE("secret_delete", "DELETE", "/platform/objects/{id}/secrets/{secret_id}"),
        ENABLE("enable", "POST", "/platform/objects/{id}/enable"),
        DISABLE("disable", "POST", "/platform/objects/{id}/disable"),
        TEST("test", "POST", "/platform/objects/{id}/test");

        private final String suffix;
        private final String method;
        private final String path;

        Op(String suffix, String method, String path) {
            this.suffix = suffix;
            this.method = method;
            this.path = path;
        }
    }

    /**
     * {@code <word>.<op>}: {@code object("webhook_outbound", Op.GET)} is {@code
     * webhook_outbound.get}, {@code GET /platform/objects/{id}}.
     */
    public static Route object(String word, Op op) {
        return new Route(word + "." + op.suffix, op.method, op.path, false);
    }

    /**
     * The same path under another name: {@code canvas.object_get} is {@code <word>.get}'s path,
     * renamed because {@code canvas.get} is the {@code /v1} read (D2).
     */
    public static Route objectNamed(String name, Op op) {
        return new Route(name, op.method, op.path, false);
    }
}
