<?php

declare(strict_types=1);

namespace Lingara\Cli\Store;

use Devium\Toml\Toml;
use Lingara\Cli\Core\Env;

/**
 * The config dir and `config.toml` (ADR 16.10.26ad D4). The dir is
 * `LINGARA_CONFIG_DIR`, else `$XDG_CONFIG_HOME/lingara`, else
 * `~/.config/lingara` on macOS and Linux alike, and `%APPDATA%\lingara` on
 * Windows. `config.toml` holds only non-secret per-profile settings:
 * `[profiles.<name>]` with `api_url` and `output`.
 */
final class ConfigDir
{
    public static function path(Env $env): string
    {
        $explicit = $env->get('LINGARA_CONFIG_DIR');
        if ($explicit !== null) {
            return $explicit;
        }
        if (PHP_OS_FAMILY === 'Windows') {
            return ($env->get('APPDATA') ?? (string) $env->get('USERPROFILE')) . '\\lingara';
        }
        $xdg = $env->get('XDG_CONFIG_HOME');
        if ($xdg !== null) {
            return $xdg . '/lingara';
        }
        return ($env->get('HOME') ?? self::home()) . '/.config/lingara';
    }

    /** The account's home when `HOME` is unset; ext-posix is suggested, not required. */
    private static function home(): string
    {
        $entry = function_exists('posix_getpwuid') ? posix_getpwuid(posix_geteuid()) : false;
        return is_array($entry) ? (string) $entry['dir'] : '.';
    }

    /**
     * The profile's settings; a missing or unreadable file is no settings.
     *
     * @return array{api_url?: string, output?: string}
     */
    public static function profile(string $dir, string $profile): array
    {
        $text = @file_get_contents($dir . '/config.toml');
        if ($text === false) {
            return [];
        }
        try {
            $file = Toml::decode($text, true);
        } catch (\Throwable) {
            return [];
        }
        $row = is_array($file) ? ($file['profiles'][$profile] ?? []) : [];
        $out = [];
        foreach (['api_url', 'output'] as $key) {
            if (is_array($row) && is_string($row[$key] ?? null)) {
                $out[$key] = $row[$key];
            }
        }
        return $out;
    }
}
