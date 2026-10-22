<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

use Lingara\Cli\Auth\Hints;
use Lingara\Cli\Auth\Session;
use Lingara\Cli\Auth\Tokens;
use Lingara\Cli\Http\Answer;
use Lingara\Cli\Http\Classify;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Route;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Http\WireResponse;

/**
 * What one command sees, and every authenticated call (ADR 16.10.26ad D3;
 * contract B2 rule 2). A `401` to a bearer request refreshes once (a fresh
 * `client_credentials` mint under CI) and replays once; a second `401` is
 * `auth_required`, and any other answer to the replay is classified again.
 */
final class Ctx
{
    public function __construct(
        public readonly App $app,
        public readonly Output $out,
        public string $profile,
        /** `--profile` or `LINGARA_PROFILE` chose it, so `platform.toml`'s `[platform] profile` may not (16.10.26ae D4). */
        public readonly bool $profileChosen = false,
    ) {
    }

    /** @return array{string, string}|null the CI pair, when both halves are set (D3 rule 1) */
    public function ciPair(): ?array
    {
        $id = $this->app->env->get('LINGARA_PLATFORM_CLIENT_ID');
        $secret = $this->app->env->get('LINGARA_PLATFORM_CLIENT_SECRET');
        return $id !== null && $secret !== null ? [$id, $secret] : null;
    }

    /**
     * A token-endpoint form `POST`: no bearer, classified as a token route.
     *
     * @param list<array{string, string}> $pairs
     * @param array{basic?: array{string, string}} $auth
     */
    public function tokenPost(string $name, array $pairs, array $auth = []): mixed
    {
        $route = Routes::fixed($name);
        $reply = $this->app->http->send(Request::form($route, $pairs), $auth);
        return $reply->ok() ? self::bodyJson($reply, $route) : throw self::refusal($route, $reply, false);
    }

    /** @param array{string, string} $ci */
    private function mint(array $ci): Tokens
    {
        $pairs = [['grant_type', 'client_credentials'], ['scope', Tokens::SCOPES]];
        return Tokens::read($this->tokenPost('oauth.token', $pairs, ['basic' => $ci]));
    }

    private function open(): Session
    {
        $ci = $this->ciPair();
        if ($ci !== null) {
            $tokens = $this->mint($ci);
            return new Session($tokens->access, $tokens->scope ?? Tokens::SCOPES, $ci);
        }
        $record = $this->app->stores->load($this->profile) ?? throw Fail::auth('not signed in: run `lingara login`');
        $session = $this->app->session = Session::stored($record);
        $expires = Tokens::parseTime($record['access_expires_at']);
        if ($expires === null || $expires - $this->app->http->clock->now() < Tokens::REFRESH_SKEW_SECS) {
            $this->refresh();
        }
        return $this->app->session ?? $session;
    }

    /** The bearer token, opening the session on first use. */
    public function bearer(): string
    {
        $this->app->session ??= $this->open();
        return $this->app->session->access;
    }

    /** Rule 2's one refresh: the stored refresh token, or a fresh CI mint. A refused refresh never deletes the stored credential (D3). */
    private function refresh(): void
    {
        $session = $this->app->session ?? throw Fail::auth('not signed in: run `lingara login`');
        if ($session->ci !== null) {
            $session->access = $this->mint($session->ci)->access;
            return;
        }
        $record = (array) $session->record;
        $pairs = [['grant_type', 'refresh_token'], ['refresh_token', $record['refresh_token'] ?? ''], ['client_id', $this->app->endpoints['client_id']]];
        try {
            $answer = $this->tokenPost('oauth.token', $pairs);
        } catch (Fail $fail) {
            // A refused refresh says "run `lingara login`", unless it is D8's dark signal.
            throw $fail->exit === ExitCode::AuthRequired ? $fail->hint('run `lingara login`') : $fail;
        }
        $record = Tokens::read($answer)->apply($record, $this->app->http->clock->now());
        $this->saveRecord($record);
        $this->app->session = Session::stored($record);
    }

    /** @param array<string, string> $record writes the profile record, printing the store's one notice */
    public function saveRecord(array $record): void
    {
        $notice = $this->app->stores->save($this->profile, $record);
        if ($notice !== null) {
            $this->out->note($notice);
        }
    }

    /** One authenticated call: send, refresh and replay once on a `401`, classify anything else. */
    public function call(Request $request): mixed
    {
        $reply = $this->app->http->send($request, ['bearer' => $this->bearer()]);
        if ($reply->ok()) {
            return self::bodyJson($reply, $request->route);
        }
        if (Classify::classify(new Answer($request->route, $reply, true)) !== null) {
            throw self::refusal($request->route, $reply, true);
        }
        $this->refresh();
        $replay = $this->app->http->send($request, ['bearer' => $this->bearer()]);
        if ($replay->ok()) {
            return self::bodyJson($replay, $request->route);
        }
        if ($replay->status === 401) {
            throw Fail::auth('the platform refused the credentials after a refresh: run `lingara login`');
        }
        throw self::refusal($request->route, $replay, true);
    }

    /** The email `--confirm` and the typed line are compared against. */
    public function email(): string
    {
        $this->bearer();
        $known = $this->app->session?->email;
        if ($known !== null) {
            return $known;
        }
        $email = Json::str($this->call(new Request(Routes::fixed('developer.me'))), 'email');
        if ($this->app->session !== null) {
            $this->app->session->email = $email;
        }
        return $email;
    }

    /** A 2xx body as JSON; an empty one is `{}`. */
    private static function bodyJson(WireResponse $reply, Route $route): mixed
    {
        if (trim($reply->body) === '') {
            return new \stdClass();
        }
        return Json::isJson($reply->body) ? Json::decode($reply->body) : throw Fail::failure("unexpected response from {$route->name}");
    }

    /** The refusal for a non-2xx reply that is not rule 2's. */
    public static function refusal(Route $route, WireResponse $reply, bool $bearer): Fail
    {
        $a = new Answer($route, $reply, $bearer);
        $fail = Hints::advise(Classify::failFor($a, Classify::classify($a) ?? ExitCode::AuthRequired));
        return $a->member('lingara_code') === 'developer_account_required' ? $fail->hint(Hints::PORTAL_LINE) : $fail;
    }
}
