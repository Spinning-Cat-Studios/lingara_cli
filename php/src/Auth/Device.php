<?php

declare(strict_types=1);

namespace Lingara\Cli\Auth;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;

/**
 * `login --device` (RFC 8628, 16.10.26u §7): for SSH sessions and machines
 * without a browser. `authorization_pending` keeps polling, `slow_down` adds
 * 5 s, `expired_token` and `access_denied` are `auth_required`, and any
 * answer carrying B4's `lingara_code` is `platform_disabled` (D8).
 */
final class Device
{
    private const GRANT = 'urn:ietf:params:oauth:grant-type:device_code';

    /** The token response once the developer has approved the code. */
    public static function login(Ctx $ctx): mixed
    {
        $clientId = $ctx->app->endpoints['client_id'];
        $start = $ctx->tokenPost('oauth.device_authorization', [['client_id', $clientId], ['scope', Tokens::SCOPES]]);
        $vars = ['verification_uri' => Json::str($start, 'verification_uri'), 'user_code' => Json::str($start, 'user_code')];
        $ctx->out->note(Assets::message('login_device_code', $vars));
        $complete = Json::opt($start, 'verification_uri_complete');
        if ($complete !== null) {
            $ctx->out->note($complete);
        }
        $interval = Json::get($start, 'interval');
        $expires = Json::get($start, 'expires_in');
        return self::poll($ctx, Json::str($start, 'device_code'), is_int($interval) ? $interval : 5, is_int($expires) ? $expires : 900);
    }

    /** What one refused poll means: keep polling (`'pending'`, `'slow_down'`), or stop with a refusal (null: classify it). */
    private static function next(?string $error, bool $live): string|Fail|null
    {
        $waiting = $error === 'authorization_pending' || $error === 'slow_down';
        if ($waiting && $live) {
            return $error;
        }
        if ($waiting || $error === 'expired_token') {
            return Fail::auth('the code expired before it was approved; run `lingara login --device` again');
        }
        return $error === 'access_denied' ? new Fail(ExitCode::AuthRequired, 'access_denied', 'sign-in was declined') : null;
    }

    private static function poll(Ctx $ctx, string $deviceCode, int $interval, int $expiresIn): mixed
    {
        $route = Routes::fixed('oauth.token');
        $request = Request::form($route, [['grant_type', self::GRANT], ['device_code', $deviceCode], ['client_id', $ctx->app->endpoints['client_id']]]);
        for ($waited = 0;;) {
            $ctx->app->http->clock->sleep($interval);
            $waited += $interval;
            $reply = $ctx->app->http->send($request);
            if ($reply->ok()) {
                return Json::decode($reply->body) ?? new \stdClass();
            }
            $body = Json::decode($reply->body);
            // B4's `lingara_code` outranks any RFC 8628 error beside it (D8).
            $verdict = self::next(Json::get($body, 'lingara_code') === null ? Json::opt($body, 'error') : null, $waited < $expiresIn);
            if ($verdict === 'slow_down') {
                $interval += 5;
            } elseif ($verdict instanceof Fail) {
                throw $verdict;
            } elseif ($verdict === null) {
                throw Ctx::refusal($route, $reply, false);
            }
        }
    }
}
