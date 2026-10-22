<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Json;

/**
 * A field node's op body: the web's `specFromFields` then `specBody`
 * (`testRun.ts`, `plan.ts`), over the node's saved fields. A create's client
 * is always `billing_mode: allowance`; no body carries `enabled`,
 * `api_version` or `client_type`. The app manifest is `lingara.v1`'s
 * `manifest()` wire form, with the defaults it fills. A field of the wrong
 * shape throws `\InvalidArgumentException`, which the planner refuses by node.
 */
final class Spec
{
    private static function text(mixed $v, string $what): string
    {
        return is_string($v) ? $v : throw new \InvalidArgumentException("{$what} must be a string");
    }

    /** @return list<string> */
    private static function texts(mixed $v, string $what): array
    {
        if (!is_array($v) || count(array_filter($v, 'is_string')) !== count($v)) {
            throw new \InvalidArgumentException("{$what} must be an array of strings");
        }
        return array_values($v);
    }

    /** A plain string is `{locale: s}`; a map must hold strings only. */
    private static function localeText(mixed $v, string $locale, string $what): \stdClass
    {
        if (is_string($v)) {
            return (object) [$locale => $v];
        }
        if ($v instanceof \stdClass && count(array_filter(get_object_vars($v), 'is_string')) === count(get_object_vars($v))) {
            return clone $v;
        }
        throw new \InvalidArgumentException("{$what} must be a string or an object of strings");
    }

    /** @return array<string, mixed> */
    public static function clientBody(\stdClass $fields, bool $create): array
    {
        $body = ['name' => self::text(Json::get($fields, 'name'), 'name'), 'scopes' => self::texts(Json::get($fields, 'scopes'), 'scopes')];
        $uris = Json::get($fields, 'redirectUris');
        if ($uris !== null) {
            $body['redirect_uris'] = self::texts($uris, 'redirectUris');
        }
        if ($create) {
            $body['billing_mode'] = 'allowance';
        }
        return $body;
    }

    /** @return array<string, mixed> */
    public static function webhookBody(\stdClass $fields): array
    {
        return ['url' => self::text(Json::get($fields, 'url'), 'url'), 'event_types' => self::texts(Json::get($fields, 'events'), 'events')];
    }

    /** @return array<string, mixed> */
    public static function appBody(\stdClass $fields): array
    {
        return ['manifest' => self::manifest(Json::get($fields, 'manifest'))];
    }

    /** @return array<string, mixed> `lingara.v1`'s `manifest()`, in the order it writes the members */
    public static function manifest(mixed $value): array
    {
        if (!$value instanceof \stdClass) {
            throw new \InvalidArgumentException('manifest must be an object');
        }
        $locale = property_exists($value, 'defaultLocale') ? self::text($value->defaultLocale, 'defaultLocale') : 'en';
        $optional = static fn (string $key): array => Json::get($value, $key) === null ? [] : self::texts(Json::get($value, $key), $key);
        return [
            'manifest_version' => 1,
            'default_locale' => $locale,
            'name' => self::localeText(Json::get($value, 'name'), $locale, 'name'),
            'description' => self::localeText(Json::get($value, 'description'), $locale, 'description'),
            'render_url' => self::text(Json::get($value, 'renderUrl'), 'renderUrl'),
            'slots' => self::texts(Json::get($value, 'slots'), 'slots'),
            'context' => $optional('context'),
            'scopes' => $optional('scopes'),
            'tutor_note' => Json::get($value, 'tutorNote') === true,
            ...self::listing($value, $locale),
        ];
    }

    /** @return array<string, mixed> the listing's three members, each written only when its option is set */
    private static function listing(\stdClass $o, string $locale): array
    {
        $out = [];
        if (property_exists($o, 'longDescription')) {
            $out['long_description'] = self::localeText($o->longDescription, $locale, 'longDescription');
        }
        $d = Json::get($o, 'developer');
        if ($d instanceof \stdClass) {
            $out['developer'] = ['name' => self::text(Json::get($d, 'name'), 'developer.name')];
            foreach (['url' => 'url', 'supportUrl' => 'support_url'] as $from => $to) {
                if (property_exists($d, $from)) {
                    $out['developer'][$to] = self::text($d->{$from}, $from);
                }
            }
        }
        if (is_array(Json::get($o, 'images'))) {
            $out['images'] = array_map(static fn (mixed $i): array => [
                'sha256' => self::text(Json::get($i, 'sha256'), 'images[].sha256'),
                'role' => self::text(Json::get($i, 'role'), 'images[].role'),
                'alt' => self::localeText(Json::get($i, 'alt'), $locale, 'images[].alt'),
            ], $o->images);
        }
        return $out;
    }

    /** @param array<string, mixed> $body the name an app op registers under: the manifest's in its default locale */
    public static function appName(array $body, string $fallback): string
    {
        $manifest = $body['manifest'] ?? [];
        $names = is_array($manifest) ? Json::object($manifest['name'] ?? null) : new \stdClass();
        $locale = is_array($manifest) && is_string($manifest['default_locale'] ?? null) ? $manifest['default_locale'] : 'en';
        $named = Json::get($names, $locale) ?? current(array_filter(get_object_vars($names), 'is_string'));
        return is_string($named) ? $named : $fallback;
    }
}
