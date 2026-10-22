<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

/**
 * Every path this kit sends, spelled once and keyed by
 * `conformance/routes.toml`'s logical names (ADR 16.10.26ad D2). The mock
 * matches by path, so a spelling that drifts from that file meets a `599`
 * and fails conformance. The tables are data, so a route is never a branch.
 */
final class Routes
{
    private const OBJECTS = '/platform/objects';
    private const OBJECT = '/platform/objects/{id}';
    private const VERSION = '/platform/objects/{id}/versions/{semver}';
    private const CANVAS = '/v1/canvases/{id}';
    private const DEV = '/platform/objects/{id}/dev';

    /** The routes that are not a kind's `/platform/objects` family. */
    private const FIXED = [
        'oauth.authorize' => ['GET', '/oauth/authorize', Route::PORTAL],
        'oauth.device_authorization' => ['POST', '/oauth/device_authorization', Route::API],
        'oauth.token' => ['POST', '/oauth/token', Route::API],
        'oauth.revoke' => ['POST', '/oauth/revoke', Route::API],
        'developer.me' => ['GET', '/platform/developer', Route::API],
        'canvas.list' => ['GET', '/v1/canvases', Route::API],
        'canvas.create' => ['POST', '/v1/canvases', Route::API],
        'canvas.get' => ['GET', self::CANVAS, Route::API],
        'canvas.rename' => ['PATCH', self::CANVAS, Route::API],
        'canvas.put_draft' => ['PUT', '/v1/canvases/{id}/draft', Route::API],
        'canvas.dry_run' => ['POST', '/v1/canvases/dry-run', Route::API],
        'canvas.activate' => ['POST', '/v1/canvases/{id}/activate', Route::API],
        // `?kind=canvas&runtime_id=…` is the caller's query (D2).
        'canvas.lookup' => ['GET', self::OBJECTS, Route::API],
        // The dev version and its session (16.10.26v §12; 16.10.26ae R1–R3).
        'app_dev.get' => ['GET', self::DEV, Route::API],
        'app_dev.patch' => ['PATCH', self::DEV, Route::API],
        'app_dev.session_open' => ['POST', self::DEV . '/session', Route::API],
        'app_dev.session_renew' => ['POST', self::DEV . '/session/{session_id}/renew', Route::API],
        'app_dev.session_end' => ['DELETE', self::DEV . '/session/{session_id}', Route::API],
    ];

    /** One operation of the `/platform/objects` family (16.10.26v §8). */
    private const OBJECT_OPS = [
        'list' => ['GET', self::OBJECTS],
        'get' => ['GET', self::OBJECT],
        'create' => ['POST', self::OBJECTS],
        'rename' => ['PATCH', self::OBJECT],
        'version_get' => ['GET', self::VERSION],
        'version_put' => ['PUT', self::VERSION],
        'version_create' => ['POST', '/platform/objects/{id}/versions'],
        'publish' => ['POST', self::VERSION . '/publish'],
        'versions' => ['GET', '/platform/objects/{id}/versions'],
        'submit' => ['POST', self::VERSION . '/submit'],
        'withdraw' => ['POST', self::VERSION . '/withdraw'],
        'delete' => ['DELETE', self::OBJECT],
        'delete_version' => ['DELETE', self::VERSION],
        'secret_create' => ['POST', '/platform/objects/{id}/secrets'],
        'secret_delete' => ['DELETE', '/platform/objects/{id}/secrets/{secret_id}'],
        'enable' => ['POST', '/platform/objects/{id}/enable'],
        'disable' => ['POST', '/platform/objects/{id}/disable'],
        'test' => ['POST', '/platform/objects/{id}/test'],
    ];

    public static function fixed(string $name): Route
    {
        [$method, $path, $base] = self::FIXED[$name];
        return new Route($name, $method, $path, $base);
    }

    /** `<word>.<op>`: `object('webhook_outbound', 'get')` is `GET /platform/objects/{id}`. */
    public static function object(string $word, string $op): Route
    {
        return self::objectNamed("{$word}.{$op}", $op);
    }

    /** The same path under another name: `canvas.object_get` is `<word>.get`'s path. */
    public static function objectNamed(string $name, string $op): Route
    {
        [$method, $path] = self::OBJECT_OPS[$op];
        return new Route($name, $method, $path);
    }
}
