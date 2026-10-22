<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * JSON as this kit holds it: objects decode to `stdClass`, never to PHP
 * arrays, so `{}` and `[]` stay apart and a server value prints back as it
 * arrived (contract B1: a kit never renames or reshapes a field). Encoding
 * matches the other kits byte for byte: no escaped slashes or Unicode, and
 * `1.0` stays `1.0`.
 */
final class Json
{
    private const FLAGS = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION;

    public static function encode(mixed $value): string
    {
        $text = json_encode($value, self::FLAGS);
        return $text === false ? 'null' : $text;
    }

    /** The value, or null when `$text` is not JSON (which `isJson` tells apart from a JSON `null`). */
    public static function decode(string $text): mixed
    {
        return json_decode($text, false, 512, JSON_BIGINT_AS_STRING);
    }

    public static function isJson(string $text): bool
    {
        json_decode($text);
        return json_last_error() === JSON_ERROR_NONE;
    }

    /** An object's member, or null. */
    public static function get(mixed $object, string $key): mixed
    {
        return $object instanceof \stdClass && property_exists($object, $key) ? $object->{$key} : null;
    }

    /** A string member, or ''. */
    public static function str(mixed $object, string $key): string
    {
        $value = self::get($object, $key);
        return is_string($value) ? $value : '';
    }

    /** A string member, or null. */
    public static function opt(mixed $object, string $key): ?string
    {
        $value = self::get($object, $key);
        return is_string($value) ? $value : null;
    }

    /** @return list<mixed> an array member's items, or none */
    public static function items(mixed $object, string $key): array
    {
        $value = self::get($object, $key);
        return is_array($value) ? array_values($value) : [];
    }

    /** @return list<string> an array member's string items */
    public static function strings(mixed $value): array
    {
        return is_array($value) ? array_values(array_filter($value, 'is_string')) : [];
    }

    /** `$value` when it is an object, else a new empty one. */
    public static function object(mixed $value): \stdClass
    {
        return $value instanceof \stdClass ? $value : new \stdClass();
    }
}
