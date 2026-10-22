<?php

declare(strict_types=1);

namespace Lingara\Cli\Auth;

use Lingara\Cli\Core\Fail;

/**
 * The one line of advice a refusal's code earns (ADR 16.10.26ad D3, D5, D6).
 * Keyed by code, so a reworded server sentence changes nothing.
 */
final class Hints
{
    public const PORTAL_LINE = 'create your developer account at https://platform.getlingara.com';

    private const BY_CODE = [
        'developer_not_found' => self::PORTAL_LINE,
        'developer_account_required' => self::PORTAL_LINE,
        'developer_terms_outdated' => 'accept the updated developer terms at https://platform.getlingara.com',
        'delete_confirmation_mismatch' => 'the platform holds a different email for you; run `lingara whoami`',
        'invalid_scope' => 'recreate this client with `lingara client create --ci`',
    ];

    /** `$fail` with its code's line, once. */
    public static function advise(Fail $fail): Fail
    {
        $line = self::BY_CODE[$fail->errorCode] ?? null;
        return $line === null ? $fail : $fail->hint($line);
    }
}
