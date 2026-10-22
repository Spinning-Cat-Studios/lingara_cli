<?php

declare(strict_types=1);

namespace Lingara\Cli\Auth;

use Lingara\Cli\Core\Json;

/** A token response, and the profile record it becomes (ADR 16.10.26ad D4). */
final class Tokens
{
    /** The four scopes, in one grant (16.10.26u §3). */
    public const SCOPES = 'platform:read platform:write canvas:read canvas:write';

    /** A stored access token within this many seconds of expiry is refreshed first (B6.3). */
    public const REFRESH_SKEW_SECS = 60;

    public function __construct(public readonly string $access, public readonly ?string $refresh, public readonly ?string $scope, public readonly int $expiresIn)
    {
    }

    /** RFC 6749 §5.1, read leniently: a missing `expires_in` is an hour. */
    public static function read(mixed $value): self
    {
        $expires = Json::get($value, 'expires_in');
        return new self(Json::str($value, 'access_token'), Json::opt($value, 'refresh_token'), Json::opt($value, 'scope'), is_int($expires) ? $expires : 3600);
    }

    /**
     * `$record` with these tokens: the refresh token rotates when one was
     * sent, and `scopes` is rewritten whenever the answer carried `scope`.
     *
     * @param array<string, string> $record
     * @return array<string, string>
     */
    public function apply(array $record, int $now): array
    {
        $record['access_token'] = $this->access;
        if ($this->refresh !== null) {
            $record['refresh_token'] = $this->refresh;
        }
        if ($this->scope !== null) {
            $record['scopes'] = $this->scope;
        }
        $record['access_expires_at'] = self::rfc3339($now + $this->expiresIn);
        return $record;
    }

    /** `YYYY-MM-DDTHH:MM:SSZ`, the one form `access_expires_at` takes. */
    public static function rfc3339(int $secs): string
    {
        return gmdate('Y-m-d\TH:i:s\Z', $secs);
    }

    /** Seconds since the epoch, or null when `$text` is not RFC 3339. */
    public static function parseTime(string $text): ?int
    {
        if (preg_match('/^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}/', $text) !== 1) {
            return null;
        }
        $at = date_create_immutable(substr($text, 0, 19) . 'Z', new \DateTimeZone('UTC'));
        return $at === false ? null : $at->getTimestamp();
    }
}
