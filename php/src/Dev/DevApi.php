<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;

/**
 * The five `app_dev.*` routes (Backend 16.10.26v §12; ADR 16.10.26ae
 * R1–R3), one function each, plus the read-then-patch every writer of the
 * dev version uses: a `409 version_conflict` re-reads once and retries, and
 * a second one is the server's `refused`.
 */
final class DevApi
{
    /** @param array<string, string> $vars */
    private static function call(Ctx $ctx, string $name, array $vars, mixed $body = null): mixed
    {
        return $ctx->call(Request::json(Routes::fixed($name), $vars, $body));
    }

    /** `GET …/dev`: the manifest, `render_url`, `draft_version`, the session. */
    public static function get(Ctx $ctx, string $id): mixed
    {
        return self::call($ctx, 'app_dev.get', ['id' => $id]);
    }

    /**
     * Reads `draft_version`, then patches `$build(draft_version)`; one
     * re-read and retry on `version_conflict`.
     *
     * @param \Closure(mixed): array<string, mixed> $build
     */
    public static function patchWithRetry(Ctx $ctx, string $id, \Closure $build): mixed
    {
        $draft = Json::get(self::get($ctx, $id), 'draft_version');
        try {
            return self::call($ctx, 'app_dev.patch', ['id' => $id], $build($draft));
        } catch (Fail $fail) {
            if ($fail->errorCode !== 'version_conflict') {
                throw $fail;
            }
            return self::call($ctx, 'app_dev.patch', ['id' => $id], $build(Json::get(self::get($ctx, $id), 'draft_version')));
        }
    }

    /**
     * `POST …/dev/session {take_over, credentials}`: the session and the
     * secrets it minted, which live only in this process's memory and the
     * app's environment (D5).
     *
     * @return array{id: string, lease_secs: int, expires_at: string, app_secret: string, client_secret: ?string}
     */
    public static function open(Ctx $ctx, string $id, bool $takeOver, bool $client): array
    {
        $body = ['take_over' => $takeOver];
        if ($client) {
            $body['credentials'] = ['client'];
        }
        $answer = self::call($ctx, 'app_dev.session_open', ['id' => $id], $body);
        $lease = Json::get($answer, 'lease_secs');
        return [
            'id' => Json::str($answer, 'session_id'),
            'lease_secs' => is_int($lease) && $lease > 0 ? $lease : 300,
            'expires_at' => Json::str($answer, 'expires_at'),
            'app_secret' => Json::str(Json::get($answer, 'app_secret'), 'secret'),
            'client_secret' => Json::opt(Json::get($answer, 'client_secret'), 'secret'),
        ];
    }

    public static function renew(Ctx $ctx, string $id, string $sessionId): mixed
    {
        return self::call($ctx, 'app_dev.session_renew', ['id' => $id, 'session_id' => $sessionId]);
    }

    /** `DELETE …/dev/session/{sid}`: parks, keeps `render_url`, revokes the session's credentials. */
    public static function end(Ctx $ctx, string $id, string $sessionId): mixed
    {
        return self::call($ctx, 'app_dev.session_end', ['id' => $id, 'session_id' => $sessionId]);
    }
}
