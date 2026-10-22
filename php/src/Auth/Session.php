<?php

declare(strict_types=1);

namespace Lingara\Cli\Auth;

/**
 * The signed-in session of one run (ADR 16.10.26ad D3): CI client
 * credentials held in memory only, or the stored profile record.
 */
final class Session
{
    /**
     * @param array{string, string}|null $ci `LINGARA_PLATFORM_CLIENT_ID` / `_SECRET`
     * @param array<string, string>|null $record the stored profile record
     */
    public function __construct(
        public string $access,
        public string $scopes,
        public ?array $ci = null,
        public ?array $record = null,
        /** The email `--confirm` is checked against: the record's, or under CI `developer.me`'s, read once (D5 step 2). */
        public ?string $email = null,
    ) {
    }

    /** @param array<string, string> $record */
    public static function stored(array $record): self
    {
        return new self($record['access_token'], $record['scopes'], null, $record, $record['email']);
    }
}
