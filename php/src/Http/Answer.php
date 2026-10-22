<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

use Lingara\Cli\Core\Json;

/** A non-2xx answer, with what the classifier's rules read about the request behind it. */
final class Answer
{
    private mixed $parsed;

    /** The logical route: what `unexpected response from {route}` names. */
    public readonly string $route;

    /** `oauth.token` or `oauth.device_authorization`. */
    public readonly bool $tokenRoute;

    /** Under `/platform/`, where the dark 404 is spoken. */
    public readonly bool $platformRoute;

    public readonly int $status;

    public readonly string $body;

    public readonly ?int $retryAfter;

    /** `$bearer`: the request carried `Authorization: Bearer` (rule 2). */
    public function __construct(Route $route, WireResponse $reply, public readonly bool $bearer)
    {
        [$this->route, $this->tokenRoute, $this->platformRoute] = [$route->name, $route->isToken(), $route->isPlatform()];
        [$this->status, $this->body] = [$reply->status, $reply->body];
        $retry = trim((string) $reply->header('retry-after'));
        $this->retryAfter = ctype_digit($retry) ? (int) $retry : null;
        $this->parsed = Json::decode($reply->body);
    }

    /** A string member of the JSON body, or null. */
    public function member(string $key): ?string
    {
        return Json::opt($this->parsed, $key);
    }

    /** `{code, error}`, the platform's and `/v1`'s envelope. */
    public function coded(): bool
    {
        return $this->member('code') !== null && $this->member('error') !== null;
    }

    /** RFC 6749 / 8628: an `error` member and no `code`. */
    public function oauthEnvelope(): bool
    {
        return $this->member('error') !== null && $this->member('code') === null;
    }

    public function emptyBody(): bool
    {
        return trim($this->body) === '';
    }
}
