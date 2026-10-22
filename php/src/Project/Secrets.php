<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

/**
 * D4's `platform_toml_secret` (ADR 16.10.26ae): the first value or key, in
 * file order, that looks like a secret. A value is one when it matches
 * `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id (`lgr_cid_…`);
 * a key is one when its `_`-separated words include `secret`, `token` or
 * `password`, case-insensitively (`CLIENT_SECRET`, `api_token`; never
 * `TOKENIZER`). The refusal names the key, never the value.
 */
final class Secrets
{
    private const SECRET_WORDS = ['secret', 'token', 'password'];

    public static function secretValue(string $text): bool
    {
        preg_match_all('/lgr_([a-z]+)_[A-Za-z0-9]{8,}/', $text, $matches);
        foreach ($matches[1] as $kind) {
            if ($kind !== 'cid') {
                return true;
            }
        }
        return false;
    }

    public static function secretKey(string $key): bool
    {
        foreach (explode('_', $key) as $word) {
            if (in_array(strtolower($word), self::SECRET_WORDS, true)) {
                return true;
            }
        }
        return false;
    }

    /** The dotted path of the first secret-shaped key or value, if any. */
    public static function first(mixed $value, string $at = ''): ?string
    {
        if (is_string($value)) {
            return self::secretValue($value) ? $at : null;
        }
        if (is_array($value)) {
            foreach ($value as $item) {
                $found = self::first($item, $at);
                if ($found !== null) {
                    return $found;
                }
            }
            return null;
        }
        return $value instanceof \stdClass ? self::inTable($value, $at) : null;
    }

    private static function inTable(\stdClass $table, string $at): ?string
    {
        foreach (get_object_vars($table) as $key => $value) {
            $path = $at === '' ? (string) $key : "{$at}.{$key}";
            $found = self::secretKey((string) $key) ? $path : self::first($value, $path);
            if ($found !== null) {
                return $found;
            }
        }
        return null;
    }
}
