<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * The one response classifier (ADR 16.10.26ad D8; contract B2). Its eight
 * rules are **data**: an ordered list of `(predicate, verdict)` entries, the
 * first match winning and rule 8 the catch-all. 16.10.26ae D10's rule 4a is
 * one more entry, after rule 4. A verdict is an exit, or null for rule 2's
 * "refresh once and replay".
 */
final class Classify
{
    /** `conformance/routes.toml`'s two codes, as this kit keys them. */
    public const PLATFORM_DISABLED_CODE = 'platform_disabled';
    public const PLATFORM_ACCESS_CODE = 'platform_access_not_enabled';

    /**
     * Rule 4a (16.10.26ae D10): a dev-session `409` is a row of its own,
     * keyed on `(route, body code)`, because a script branches on "someone
     * else is developing this app" where it would not on any other refusal.
     */
    private const DEV_SESSION_CODES = [
        ['app_dev.session_open', 'dev_session_held', ExitCode::DevSessionHeld],
        ['app_dev.patch', 'dev_session_held', ExitCode::DevSessionHeld],
        ['app_dev.session_renew', 'dev_session_not_live', ExitCode::DevSessionLost],
        ['app_dev.session_renew', 'dev_session_expired', ExitCode::DevSessionLost],
    ];

    /** The verdict: an exit, or null for a refresh. */
    public static function classify(Answer $a): ?ExitCode
    {
        foreach (self::rules() as [$matches, $verdict]) {
            if ($matches($a)) {
                return $verdict($a);
            }
        }
        return ExitCode::Failure;
    }

    /** @return list<array{\Closure(Answer): bool, \Closure(Answer): ?ExitCode}> */
    private static function rules(): array
    {
        $exit = static fn (ExitCode $e): \Closure => static fn (Answer $a): ExitCode => $e;
        return [
            [self::platformDisabled(...), $exit(ExitCode::PlatformDisabled)],
            [static fn (Answer $a): bool => $a->status === 401 && $a->bearer && !$a->tokenRoute, static fn (Answer $a): ?ExitCode => null],
            [static fn (Answer $a): bool => $a->tokenRoute && $a->status >= 400 && $a->status < 500 && $a->status !== 429 && $a->oauthEnvelope(), $exit(ExitCode::AuthRequired)],
            [static fn (Answer $a): bool => $a->status === 404 && $a->coded(), self::codedNotFound(...)],
            [static fn (Answer $a): bool => self::devSessionRow($a) !== null, static fn (Answer $a): ?ExitCode => self::devSessionRow($a)],
            [static fn (Answer $a): bool => $a->status === 404 && $a->emptyBody(), $exit(ExitCode::Failure)],
            [static fn (Answer $a): bool => in_array($a->status, [400, 403, 409, 422], true), $exit(ExitCode::Refused)],
            [static fn (Answer $a): bool => $a->status === 429 || $a->status >= 500, $exit(ExitCode::Unavailable)],
            [static fn (Answer $a): bool => true, $exit(ExitCode::Failure)],
        ];
    }

    private static function platformDisabled(Answer $a): bool
    {
        $code = $a->member('code');
        $dark = $a->status === 404 && $a->platformRoute && $code === self::PLATFORM_DISABLED_CODE;
        $door = $a->status === 403 && $code === self::PLATFORM_ACCESS_CODE;
        $token = $a->tokenRoute && $a->member('lingara_code') === self::PLATFORM_ACCESS_CODE;
        return $dark || $door || $token;
    }

    /** Rule 4's one exception is keyed on route **and** code. */
    private static function codedNotFound(Answer $a): ExitCode
    {
        $gone = $a->route === 'developer.me' && $a->member('code') === 'developer_not_found';
        return $gone ? ExitCode::Refused : ExitCode::NotFound;
    }

    private static function devSessionRow(Answer $a): ?ExitCode
    {
        if ($a->status !== 409) {
            return null;
        }
        foreach (self::DEV_SESSION_CODES as [$route, $code, $exit]) {
            if ($route === $a->route && $code === $a->member('code')) {
                return $exit;
            }
        }
        return null;
    }

    /** The refusal B2 prints for `$exit`: the server's own code and text where it sent them, the kit's sentence where it did not. */
    public static function failFor(Answer $a, ExitCode $exit): Fail
    {
        [$code, $text] = self::spelled($a, $exit);
        if ($exit === ExitCode::Unavailable && $a->retryAfter !== null) {
            $text .= " (retry after {$a->retryAfter} s)";
        }
        $fail = new Fail($exit, $code, $text);
        // A command that re-words a refusal (apps dev's `dev_session_held`) reads the server's other members from here.
        $fail->body = $a->body;
        return $fail;
    }

    /** @return array{string, string} */
    private static function spelled(Answer $a, ExitCode $exit): array
    {
        return match ($exit) {
            ExitCode::PlatformDisabled => [self::PLATFORM_DISABLED_CODE, Assets::message('platform_disabled')],
            ExitCode::Failure => ['unexpected_response', "unexpected response from {$a->route}"],
            ExitCode::AuthRequired => [$a->member('error') ?? 'auth_required', $a->member('error_description') ?? "{$a->route} refused the credentials"],
            default => [
                $a->member('code') ?? ($exit === ExitCode::Unavailable ? 'unavailable' : 'refused'),
                $a->member('error') ?? "{$a->route} answered HTTP {$a->status}",
            ],
        };
    }
}
