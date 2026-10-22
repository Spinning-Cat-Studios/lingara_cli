<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

/** One path this kit sends, under `conformance/routes.toml`'s logical name. */
final class Route
{
    public const API = 'api';
    public const PORTAL = 'portal';

    public function __construct(
        /** The logical name: what `unexpected response from {route}` names. */
        public readonly string $name,
        public readonly string $method,
        public readonly string $path,
        public readonly string $base = self::API,
    ) {
    }

    /** `oauth.token` and `oauth.device_authorization`: no bearer, and their RFC envelope is B2 rule 3's. */
    public function isToken(): bool
    {
        return $this->name === 'oauth.token' || $this->name === 'oauth.device_authorization';
    }

    /** Under `/platform/`, where the dark 404 is spoken (B4). */
    public function isPlatform(): bool
    {
        return str_starts_with($this->path, '/platform/');
    }
}
