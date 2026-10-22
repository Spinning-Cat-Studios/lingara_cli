<?php

declare(strict_types=1);

namespace Lingara\Cli\Literal;

/**
 * The closed key map from `lingara.v1`'s `ManifestOptions` to the wire
 * manifest (spec/connection-literal.md), in `lingara.manifest`'s member
 * order. A key the map does not name is refused, never passed through.
 */
final class Convert
{
    /** Literal key → wire key, in the order the wire manifest writes them. */
    private const KEYS = [
        'defaultLocale' => 'default_locale', 'name' => 'name', 'description' => 'description', 'renderUrl' => 'render_url',
        'slots' => 'slots', 'context' => 'context', 'scopes' => 'scopes', 'tutorNote' => 'tutor_note',
        'longDescription' => 'long_description', 'developer' => 'developer', 'images' => 'images',
    ];
    private const REQUIRED = ['name', 'description', 'renderUrl', 'slots'];
    private const DEVELOPER = ['name' => 'name', 'url' => 'url', 'supportUrl' => 'support_url'];
    private const IMAGE = ['sha256' => 'sha256', 'role' => 'role', 'alt' => 'alt'];
    private const LOCALE_MAPS = ['name', 'description', 'longDescription'];

    /**
     * @param list<array<string, mixed>> $members
     * @return array<string, mixed>|null
     */
    private static function find(array $members, string $key): ?array
    {
        foreach ($members as $m) {
            if ($m['key'] === $key) {
                return $m['value'];
            }
        }
        return null;
    }

    /** @param array<string, mixed> $lit as JSON; a reference becomes its handle */
    public static function json(array $lit): mixed
    {
        return match (true) {
            array_key_exists('str', $lit) => $lit['str'],
            array_key_exists('ref', $lit) => $lit['ref'],
            array_key_exists('num', $lit) => $lit['num'],
            array_key_exists('bool', $lit) => $lit['bool'],
            array_key_exists('nil', $lit) => null,
            array_key_exists('arr', $lit) => array_map([self::class, 'json'], $lit['arr']),
            default => self::members($lit['obj']),
        };
    }

    /** @param list<array<string, mixed>> $members */
    private static function members(array $members): \stdClass
    {
        $out = new \stdClass();
        foreach ($members as $m) {
            $out->{$m['key']} = self::json($m['value']);
        }
        return $out;
    }

    /**
     * A string is the default locale's; an object passes its tags through.
     *
     * @param array<string, mixed> $lit
     */
    private static function localeMap(array $lit, string $locale): mixed
    {
        return array_key_exists('str', $lit) ? (object) [$locale => $lit['str']] : self::json($lit);
    }

    /**
     * An object whose keys must all be in `$map`, renamed through it.
     *
     * @param array<string, mixed> $lit
     * @param array<string, string> $map
     */
    private static function renamed(array $lit, array $map, string $locale): mixed
    {
        if (!isset($lit['obj'])) {
            return self::json($lit);
        }
        foreach ($lit['obj'] as $m) {
            if (!isset($map[$m['key']])) {
                throw Refusal::at('manifest_key', $m['at']);
            }
        }
        $out = new \stdClass();
        foreach ($map as $from => $to) {
            $value = self::find($lit['obj'], $from);
            if ($value !== null) {
                $out->{$to} = $from === 'alt' ? self::localeMap($value, $locale) : self::json($value);
            }
        }
        return $out;
    }

    /** @param array<string, mixed> $value */
    private static function valueOf(string $from, array $value, string $locale): mixed
    {
        if (in_array($from, self::LOCALE_MAPS, true)) {
            return self::localeMap($value, $locale);
        }
        if ($from === 'developer') {
            return self::renamed($value, self::DEVELOPER, $locale);
        }
        if ($from === 'images' && isset($value['arr'])) {
            return array_map(static fn (array $i): mixed => self::renamed($i, self::IMAGE, $locale), $value['arr']);
        }
        return self::json($value);
    }

    /**
     * A key outside the map is refused at the key; a required one missing, at the object's `{`.
     *
     * @param list<array<string, mixed>> $members
     * @param array{int, int} $pos
     */
    private static function closed(array $members, array $pos): void
    {
        foreach ($members as $m) {
            if (!isset(self::KEYS[$m['key']])) {
                throw Refusal::at('manifest_key', $m['at']);
            }
        }
        foreach (self::REQUIRED as $key) {
            if (self::find($members, $key) === null) {
                throw Refusal::at('manifest_key', $pos);
            }
        }
    }

    /**
     * The wire manifest for the `manifest` object at `$pos`.
     *
     * @param list<array<string, mixed>> $members
     * @param array{int, int} $pos
     */
    public static function manifest(array $members, array $pos): \stdClass
    {
        self::closed($members, $pos);
        $localeLit = self::find($members, 'defaultLocale');
        $locale = is_string($localeLit['str'] ?? null) ? $localeLit['str'] : 'en';
        $out = (object) ['manifest_version' => 1, 'default_locale' => $localeLit === null ? 'en' : self::json($localeLit)];
        $defaults = ['context' => [], 'scopes' => [], 'tutorNote' => false];
        foreach (array_slice(self::KEYS, 1, null, true) as $from => $to) {
            $value = self::find($members, $from);
            if ($value !== null) {
                $out->{$to} = self::valueOf($from, $value, $locale);
            } elseif (array_key_exists($from, $defaults)) {
                $out->{$to} = $defaults[$from];
            }
        }
        return $out;
    }
}
