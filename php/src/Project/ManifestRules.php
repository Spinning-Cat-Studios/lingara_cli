<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Core\Json;

/**
 * What the CLI checks in a wire manifest before it sends one (ADR
 * 16.10.26ae D6): the ephemeral render URL a version may never carry, the
 * manifest rules a warning depends on, and the warnings themselves, pinned
 * by `spec/manifest-warnings.json` (the app kits' `manifestWarnings`). The
 * server stays the authority on every rule; these run first so a refusal
 * costs no request.
 */
final class ManifestRules
{
    /** The slots a manifest may name (the app view's `AppSlotName`). */
    private const SLOTS = ['home.side', 'plans.empty_detail'];

    private const WARNING_TEXTS = [
        'single_locale' => 'the listing is in its default locale only; give name and description a second locale',
    ];

    /** The URL's host, lowercased: no scheme, user, port or path. */
    private static function host(string $url): string
    {
        $scheme = strpos($url, '://');
        $rest = $scheme === false ? $url : substr($url, $scheme + 3);
        $authority = substr($rest, 0, strcspn($rest, '/?#'));
        $hostPort = substr((string) strrchr('@' . $authority, '@'), 1);
        return strtolower(explode(':', $hostPort)[0]);
    }

    /** A quick tunnel's or the template's placeholder: never a version's URL. */
    public static function ephemeral(string $renderUrl): bool
    {
        $host = self::host($renderUrl);
        return $host === 'dev.invalid' || $host === 'trycloudflare.com' || str_ends_with($host, '.trycloudflare.com');
    }

    private static function localeMap(mixed $v): bool
    {
        $members = $v instanceof \stdClass ? get_object_vars($v) : [];
        return $members !== [] && count(array_filter($members, static fn (mixed $s): bool => is_string($s) && $s !== '')) === count($members);
    }

    private static function hasDuplicate(mixed $v): bool
    {
        return is_array($v) && count(array_unique(array_map([Json::class, 'encode'], $v))) !== count($v);
    }

    /** The first rule `$m` breaks, of the ones a warning depends on, in the app kits' order. */
    public static function brokenRule(mixed $m): ?string
    {
        $locale = Json::str($m, 'default_locale');
        $inMap = static fn (string $key): bool => !(Json::get($m, $key) instanceof \stdClass) || property_exists(Json::get($m, $key), $locale);
        $url = Json::get($m, 'render_url');
        $slots = Json::get($m, 'slots');
        $context = Json::get($m, 'context');
        $checks = [
            'manifest_version' => static fn (): bool => Json::get($m, 'manifest_version') === 1,
            'default_locale' => static fn (): bool => $locale !== '' && $inMap('name') && $inMap('description'),
            'name' => static fn (): bool => self::localeMap(Json::get($m, 'name')),
            'description' => static fn (): bool => self::localeMap(Json::get($m, 'description')),
            'render_url' => static fn (): bool => is_string($url) && preg_match('/^https:\/\//i', $url) === 1 && self::host($url) !== '',
            'slots' => static fn (): bool => is_array($slots) && $slots !== [] && array_filter($slots, static fn (mixed $s): bool => !in_array($s, self::SLOTS, true)) === [],
            'context' => static fn (): bool => $context === null && !property_exists(Json::object($m), 'context') || is_array($context) && count(array_filter($context, 'is_string')) === count($context),
            'locales' => static fn (): bool => !property_exists(Json::object($m), 'locales'),
            'duplicate' => static fn (): bool => !self::hasDuplicate($slots) && !self::hasDuplicate($context) && !self::hasDuplicate(Json::get($m, 'scopes')),
        ];
        foreach ($checks as $rule => $kept) {
            if (!$kept()) {
                return $rule;
            }
        }
        return null;
    }

    /** @return list<string> where `name` and `description` both have an entry, and `long_description` too when present and not null, in byte order */
    public static function locales(mixed $m): array
    {
        if (self::brokenRule($m) !== null) {
            return [];
        }
        $keys = static fn (mixed $v): array => $v instanceof \stdClass ? array_map('strval', array_keys(get_object_vars($v))) : [];
        $long = Json::get($m, 'long_description');
        $covered = array_filter(
            $keys(Json::get($m, 'name')),
            static fn (string $l): bool => in_array($l, $keys(Json::get($m, 'description')), true) && ($long === null || in_array($l, $keys($long), true)),
        );
        sort($covered, SORT_STRING);
        return $covered;
    }

    /** @return list<string> the listing's warnings: v1 `single_locale` alone. Never a refusal. */
    public static function warnings(mixed $m): array
    {
        $locales = self::locales($m);
        return count($locales) === 1 && $locales[0] === Json::get($m, 'default_locale') ? ['single_locale'] : [];
    }

    public static function warningText(string $code): string
    {
        return self::WARNING_TEXTS[$code] ?? '';
    }
}
