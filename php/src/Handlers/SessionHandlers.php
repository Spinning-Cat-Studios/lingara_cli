<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Auth\Device;
use Lingara\Cli\Auth\Pkce;
use Lingara\Cli\Auth\Session;
use Lingara\Cli\Auth\Tokens;
use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\TableRender;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Store\FileStore;

/**
 * `login`, `logout` and `whoami` (ADR 16.10.26ad D3), and the hidden
 * `__tree`. Under CI credentials there is nothing to sign in or out of, so
 * both exit `usage` with `login_ci_not_needed`.
 */
final class SessionHandlers
{
    private static function ciNotNeeded(): Fail
    {
        return Fail::message(ExitCode::Usage, 'login_ci_not_needed');
    }

    public static function login(Ctx $ctx, Input $in): void
    {
        if ($ctx->ciPair() !== null) {
            throw self::ciNotNeeded();
        }
        $answer = $in->flag('device') ? Device::login($ctx) : Pkce::login($ctx);
        $record = Tokens::read($answer)->apply(FileStore::record([]), $ctx->app->http->clock->now());
        // The new token, for this one read: whose is it?
        $ctx->app->session = new Session($record['access_token'], $record['scopes'], null, $record);
        $me = $ctx->call(new Request(Routes::fixed('developer.me')));
        $record['developer_id'] = Json::str($me, 'developer_id');
        $record['email'] = Json::str($me, 'email');
        $ctx->saveRecord($record);
        $ctx->out->note(Assets::message('login_signed_in', ['email' => $record['email']]));
        $ctx->out->nothing();
    }

    /** Revokes the refresh token (RFC 7009) and forgets the profile. A revoke that fails still forgets it, and warns. */
    public static function logout(Ctx $ctx, Input $in): void
    {
        if ($ctx->ciPair() !== null) {
            throw self::ciNotNeeded();
        }
        $record = $ctx->app->stores->load($ctx->profile) ?? throw Fail::auth('not signed in: nothing to sign out of');
        $pairs = [['token', $record['refresh_token']], ['token_type_hint', 'refresh_token'], ['client_id', $ctx->app->endpoints['client_id']]];
        try {
            $reply = $ctx->app->http->send(Request::form(Routes::fixed('oauth.revoke'), $pairs), ['bearer' => $record['access_token']]);
            $why = $reply->ok() ? null : "HTTP {$reply->status}";
        } catch (Fail $fail) {
            $why = $fail->getMessage();
        }
        if ($why !== null) {
            $ctx->out->note("warning: the refresh token could not be revoked ({$why}); forgetting it here anyway");
        }
        $ctx->app->stores->delete($ctx->profile);
        $ctx->app->session = null;
        $ctx->out->nothing();
    }

    /** `EMAIL  DEVELOPER  SCOPES  PROFILE`, `SCOPES` from the token response. */
    public static function whoami(Ctx $ctx, Input $in): void
    {
        $me = $ctx->call(new Request(Routes::fixed('developer.me')));
        if ($ctx->out->json) {
            $ctx->out->jsonValue($me);
            return;
        }
        $row = [TableRender::cell(Json::get($me, 'email')), TableRender::cell(Json::get($me, 'developer_id')), $ctx->app->session->scopes ?? '', $ctx->profile];
        $ctx->out->print(TableRender::render(['EMAIL', 'DEVELOPER', 'SCOPES', 'PROFILE'], [$row]));
    }

    /** `lingara __tree` (16.10.26ab D4): the registered tree as one JSON value, for `make check-tree`. */
    public static function tree(Ctx $ctx, Input $in): void
    {
        $ctx->out->jsonValue(($ctx->app->tree)());
    }
}
