<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Devium\Toml\Toml;
use Lingara\Cli\Core\Env;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;

/**
 * `platform.toml` (ADR 16.10.26ae D4): the gitignored, per-developer file
 * naming the apps a checkout works on. Found by `--config`, then
 * `LINGARA_PLATFORM_TOML`, then by walking up from the current directory as
 * Cargo does. Read in a fixed order, so the first refusal is the same in
 * every kit: TOML syntax (`platform_toml_schema`), a secret-shaped value or
 * key (`platform_toml_secret`), then the rules
 * `spec/platform-toml.schema.json` writes (`platform_toml_schema`). A
 * tracked file is only a warning (`Git`). TOML 1.0 through devium/toml
 * (ADR 16.10.26af D2).
 *
 * A file is `{file, api, profile, apps: list<row>}`; a row is the
 * `[[apps]]` table with its defaults filled (`port` 8787, `path` `.`) and
 * the example's placeholder id read as no id.
 */
final class PlatformToml
{
    public const DEFAULT_PORT = 8787;

    /** @param array<string, mixed> $row the project directory: the row's `path`, relative to the file's directory */
    public static function projectDir(string $file, array $row): string
    {
        return self::join(dirname($file), (string) $row['path']);
    }

    /** `$path` under `$dir` unless it is absolute, with `.` and `..` folded away as Node's `path.join` does. */
    public static function join(string $dir, string $path): string
    {
        $joined = self::absolute($path) ? $path : rtrim($dir, '/') . '/' . $path;
        $out = [];
        foreach (explode('/', $joined) as $i => $segment) {
            if ($segment === '..' && $out !== [] && end($out) !== '..' && end($out) !== '') {
                array_pop($out);
            } elseif ($segment !== '.' && ($segment !== '' || $i === 0)) {
                $out[] = $segment;
            }
        }
        return implode('/', $out) ?: '/';
    }

    public static function absolute(string $path): bool
    {
        return str_starts_with($path, '/') || preg_match('/^[A-Za-z]:[\\\\\/]/', $path) === 1;
    }

    /** The file's name as messages print it: relative to `$cwd` where it can be. */
    public static function shown(string $path, string $cwd): string
    {
        return str_starts_with($path, "{$cwd}/") ? substr($path, strlen($cwd) + 1) : $path;
    }

    /** The nearest `platform.toml` in `$from` or a directory above it. */
    public static function findUpward(string $from): ?string
    {
        for ($dir = $from;; $dir = dirname($dir)) {
            if (is_file("{$dir}/" . Write::FILE)) {
                return "{$dir}/" . Write::FILE;
            }
            if (dirname($dir) === $dir) {
                return null;
            }
        }
    }

    /** `--config`, then `LINGARA_PLATFORM_TOML`, then the nearest `platform.toml` at or above `$cwd`. */
    public static function locate(?string $config, Env $env, string $cwd): ?string
    {
        $explicit = $config ?? $env->get('LINGARA_PLATFORM_TOML');
        return $explicit === null ? self::findUpward($cwd) : self::join($cwd, $explicit);
    }

    public static function schemaFail(string $file, string $reason): Fail
    {
        return Fail::message(ExitCode::PlatformTomlSchema, 'platform_toml_schema', ['file' => $file, 'reason' => $reason]);
    }

    /**
     * Reads and checks `$path`; `$name` is how messages name it.
     *
     * @return array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>}
     */
    public static function load(string $path, string $name): array
    {
        $text = @file_get_contents($path);
        if ($text === false) {
            throw self::schemaFail($path, 'the file does not exist');
        }
        try {
            $value = Toml::decode($text, false);
        } catch (\Throwable $e) {
            throw self::schemaFail($name, strtok($e->getMessage(), "\n") ?: 'not TOML');
        }
        $key = Secrets::first($value);
        if ($key !== null) {
            throw Fail::message(ExitCode::PlatformTomlSecret, 'platform_toml_secret', ['file' => $name, 'key' => $key]);
        }
        $reason = Schema::check($value);
        if ($reason !== null) {
            throw self::schemaFail($name, $reason);
        }
        $platform = Json::get($value, 'platform');
        $apps = array_map([self::class, 'row'], Json::items($value, 'apps'));
        return ['file' => $path, 'api' => Json::opt($platform, 'api'), 'profile' => Json::opt($platform, 'profile'), 'apps' => $apps];
    }

    /** @return array<string, mixed> */
    private static function row(mixed $v): array
    {
        $id = Json::opt($v, 'id');
        $port = Json::get($v, 'port');
        $env = [];
        foreach (get_object_vars(Json::object(Json::get($v, 'env'))) as $key => $value) {
            $env[(string) $key] = is_string($value) ? $value : '';
        }
        return [
            'name' => Json::str($v, 'name'),
            'id' => $id === Write::PLACEHOLDER_ID ? null : $id,
            'path' => Json::opt($v, 'path') ?? '.',
            'port' => is_int($port) ? $port : self::DEFAULT_PORT,
            'start' => Json::opt($v, 'start'),
            'render_path' => Json::opt($v, 'render_path'),
            'manifest' => Json::opt($v, 'manifest'),
            'manifest_command' => Json::opt($v, 'manifest_command'),
            'credentials' => Json::strings(Json::get($v, 'credentials')),
            'env_file' => Json::opt($v, 'env_file'),
            'env' => $env,
        ];
    }
}
