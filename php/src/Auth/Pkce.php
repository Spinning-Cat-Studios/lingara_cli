<?php

declare(strict_types=1);

namespace Lingara\Cli\Auth;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Dev\Signals;
use Lingara\Cli\Http\Routes;

/**
 * `login`'s default: PKCE over a loopback redirect (RFC 8252, 16.10.26u §6).
 * The kit binds `127.0.0.1:0`, opens the portal's authorize page, and
 * accepts exactly one callback whose `state` matches and whose `iss` is the
 * portal base (RFC 9207). The listener gives up after 5 minutes, and a
 * Ctrl+C while it waits returns `interrupted` (ADR 16.10.26af D9).
 */
final class Pkce
{
    private const LISTEN_FOR_SECS = 300;
    private const CLOSE_TAB = '<!doctype html><title>Lingara</title><p>You can close this tab.</p>';

    public static function randomText(): string
    {
        return self::base64url(random_bytes(32));
    }

    /** RFC 7636's S256. */
    public static function challenge(string $verifier): string
    {
        return self::base64url(hash('sha256', $verifier, true));
    }

    private static function base64url(string $bytes): string
    {
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }

    /** The token response for the code the browser brought back. */
    public static function login(Ctx $ctx): mixed
    {
        $server = @stream_socket_server('tcp://127.0.0.1:0', $errno, $error);
        if ($server === false) {
            throw Fail::failure("could not listen on 127.0.0.1: {$error}");
        }
        $port = (int) substr((string) strrchr((string) stream_socket_get_name($server, false), ':'), 1);
        [$verifier, $state] = [self::randomText(), self::randomText()];
        $redirect = "http://127.0.0.1:{$port}/callback";
        $clientId = $ctx->app->endpoints['client_id'];
        $query = [
            ['response_type', 'code'], ['client_id', $clientId], ['redirect_uri', $redirect], ['code_challenge', self::challenge($verifier)],
            ['code_challenge_method', 'S256'], ['state', $state], ['scope', Tokens::SCOPES],
        ];
        $url = $ctx->app->http->url(Routes::fixed('oauth.authorize'), [], $query);
        $ctx->out->note($url);
        $ctx->out->note(Assets::message('login_browser_wait'));
        ($ctx->app->browser)($url);
        $code = self::wait($server, $state, rtrim($ctx->app->http->portal, '/'));
        $pairs = [['grant_type', 'authorization_code'], ['code', $code], ['redirect_uri', $redirect], ['code_verifier', $verifier], ['client_id', $clientId]];
        return $ctx->tokenPost('oauth.token', $pairs);
    }

    /** @param resource $server the code of the one callback that is ours */
    private static function wait($server, string $state, string $issuer): string
    {
        $deadline = time() + self::LISTEN_FOR_SECS;
        Signals::trap();
        try {
            while (time() < $deadline) {
                if (Signals::stopped()) {
                    throw new Fail(ExitCode::Interrupted, 'interrupted', 'interrupted');
                }
                $conn = @stream_socket_accept($server, 0.2);
                $answer = $conn === false ? null : self::answer($conn, $state, $issuer);
                if ($answer !== null) {
                    return $answer !== '' ? $answer : throw new Fail(ExitCode::AuthRequired, 'access_denied', 'sign-in was declined');
                }
            }
        } finally {
            Signals::release();
            fclose($server);
        }
        throw Fail::auth('no sign-in arrived within 5 minutes; run `lingara login` again');
    }

    /**
     * Answers one request on the loopback: the callback's code if it is
     * ours (`''` when it carries no code: declined), else null.
     *
     * @param resource $conn
     */
    private static function answer($conn, string $state, string $issuer): ?string
    {
        stream_set_timeout($conn, 5);
        $line = (string) fgets($conn, 8192);
        // The rest of the head too, so closing never resets a browser still sending it.
        do {
            $header = fgets($conn, 8192);
        } while ($header !== false && trim($header) !== '');
        $target = explode(' ', $line)[1] ?? '';
        $path = (string) parse_url($target, PHP_URL_PATH);
        parse_str((string) parse_url($target, PHP_URL_QUERY), $params);
        $iss = is_string($params['iss'] ?? null) ? rtrim($params['iss'], '/') : null;
        $ours = $path === '/callback' && ($params['state'] ?? null) === $state && $iss === $issuer;
        $status = $ours ? '200 OK' : '400 Bad Request';
        fwrite($conn, "HTTP/1.1 {$status}\r\ncontent-type: text/html\r\ncontent-length: " . strlen(self::CLOSE_TAB) . "\r\nconnection: close\r\n\r\n" . self::CLOSE_TAB);
        fclose($conn);
        if (!$ours) {
            return null;
        }
        return is_string($params['code'] ?? null) ? $params['code'] : '';
    }
}
