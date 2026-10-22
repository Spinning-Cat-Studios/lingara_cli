<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;

/**
 * Requests: one function spells a route onto a base, adds contract B5's
 * headers, retries what ADR 16.10.26ad D6 lets it retry, and hands back the
 * raw answer. Classifying it is `Classify`'s; credentials are `Ctx`'s.
 */
final class Http
{
    /** At most two retries of an idempotent request, waiting at most 30 s in all (D6). */
    private const RETRIES = 2;
    private const RETRY_BUDGET_SECS = 30;

    /** The API's base; the apps commands that read a `platform.toml` may move it (16.10.26ae D4). */
    public string $api;

    /** The portal's base, where the browser signs in. */
    public string $portal;

    /** @param array{api: string, portal: string} $bases */
    public function __construct(public Transport $transport, public Clock $clock, array $bases, public readonly string $userAgent, public readonly ?string $acceptLanguage)
    {
        [$this->api, $this->portal] = [$bases['api'], $bases['portal']];
    }

    /**
     * The route's absolute URL with `$vars` filled and `$query` appended.
     *
     * @param array<string, string> $vars
     * @param list<array{string, string}> $query
     */
    public function url(Route $route, array $vars = [], array $query = []): string
    {
        $base = rtrim($route->base === Route::PORTAL ? $this->portal : $this->api, '/');
        $path = $route->path;
        foreach ($vars as $key => $value) {
            $path = str_replace('{' . $key . '}', self::encode($value), $path);
        }
        return $base . $path . ($query === [] ? '' : '?' . self::pairs($query));
    }

    /**
     * One request, with D6's retries: a `GET`, `PUT`, `PATCH` or `DELETE`
     * answered 429 or 503 with `Retry-After` is retried up to twice within
     * 30 s. A `POST` is never retried after a response (B5).
     *
     * @param array{bearer?: string, basic?: array{string, string}} $auth
     */
    public function send(Request $request, array $auth = []): WireResponse
    {
        [$method, $url, $headers, $body] = $this->wire($request, $auth);
        $waited = 0;
        for ($attempt = 0;; $attempt++) {
            try {
                $response = $this->transport->send($method, $url, $headers, $body);
            } catch (\RuntimeException $e) {
                throw new Fail(ExitCode::Unavailable, 'unavailable', "{$request->route->name} could not be reached: {$e->getMessage()}");
            }
            if ($attempt > 0 && self::replayedDeleteDone($method, $response)) {
                return new WireResponse(204, [], '');
            }
            $wait = self::retryWait($method, $response);
            if ($wait === null || $attempt >= self::RETRIES || $waited + $wait > self::RETRY_BUDGET_SECS) {
                return $response;
            }
            $this->clock->sleep($wait);
            $waited += $wait;
        }
    }

    /**
     * @param array{bearer?: string, basic?: array{string, string}} $auth
     * @return array{string, string, array<string, string>, string}
     */
    private function wire(Request $request, array $auth): array
    {
        $headers = ['user-agent' => $this->userAgent, 'accept' => 'application/json'];
        if ($this->acceptLanguage !== null) {
            $headers['accept-language'] = $this->acceptLanguage;
        }
        if (isset($auth['bearer'])) {
            $headers['authorization'] = 'Bearer ' . $auth['bearer'];
        } elseif (isset($auth['basic'])) {
            $headers['authorization'] = 'Basic ' . base64_encode(self::encode($auth['basic'][0]) . ':' . self::encode($auth['basic'][1]));
        }
        $body = '';
        if ($request->form !== null) {
            $headers['content-type'] = 'application/x-www-form-urlencoded';
            $body = self::pairs($request->form);
        } elseif ($request->json !== null) {
            $headers['content-type'] = 'application/json';
            $body = Json::encode($request->json);
        }
        return [$request->route->method, $this->url($request->route, $request->vars, $request->query), $headers, $body];
    }

    private static function retryWait(string $method, WireResponse $response): ?int
    {
        $idempotent = in_array($method, ['GET', 'PUT', 'PATCH', 'DELETE'], true);
        if (!$idempotent || !in_array($response->status, [429, 503], true)) {
            return null;
        }
        $after = trim((string) $response->header('retry-after'));
        return ctype_digit($after) ? (int) $after : null;
    }

    /** A `DELETE` replayed after it succeeded answers a coded 404 (D6). */
    private static function replayedDeleteDone(string $method, WireResponse $response): bool
    {
        return $method === 'DELETE' && $response->status === 404 && Json::opt(Json::decode($response->body), 'code') !== null;
    }

    /** RFC 3986 unreserved characters pass; everything else is `%XX`. */
    public static function encode(string $text): string
    {
        return rawurlencode($text);
    }

    /** @param list<array{string, string}> $pairs */
    public static function pairs(array $pairs): string
    {
        return implode('&', array_map(static fn (array $p): string => self::encode($p[0]) . '=' . self::encode($p[1]), $pairs));
    }
}
