<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * Opening `platform.toml` for a command and picking its row (ADR 16.10.26ae
 * D4, D7 step 1). Opening runs every refusal before any request, warns once
 * when git tracks the file, and applies its `[platform]` table: `api` unless
 * `LINGARA_API_URL` is set, `profile` unless `--profile` or
 * `LINGARA_PROFILE` chose one.
 */
final class Rows
{
    /** The process's directory, through any symlink, so a row's path compares. */
    public static function cwd(): string
    {
        $cwd = (string) getcwd();
        return realpath($cwd) ?: $cwd;
    }

    public static function real(string $path): string
    {
        return realpath($path) ?: $path;
    }

    /** The file `--config`, `LINGARA_PLATFORM_TOML` or the walk up names, if any. */
    public static function located(Ctx $ctx, ?string $config, string $dir): ?string
    {
        return PlatformToml::locate($config, $ctx->app->env, $dir);
    }

    /**
     * Reads and checks the file at `$path`, warns if it is tracked, and applies its `[platform]` table.
     *
     * @return array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>}
     */
    public static function loadAt(Ctx $ctx, string $path, string $dir): array
    {
        $name = PlatformToml::shown($path, $dir);
        $toml = PlatformToml::load($path, $name);
        if (Git::tracked($path)) {
            $ctx->out->note(Assets::message('platform_toml_tracked', ['path' => $name]));
        }
        if ($ctx->app->env->get('LINGARA_API_URL') === null && $toml['api'] !== null) {
            $ctx->app->http->api = $toml['api'];
        }
        if (!$ctx->profileChosen && $toml['profile'] !== null) {
            $ctx->profile = $toml['profile'];
        }
        return $toml;
    }

    /**
     * The command's `platform.toml`, which must exist, and the directory it was looked for from.
     *
     * @return array{array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>}, string}
     */
    public static function open(Ctx $ctx, ?string $config): array
    {
        $dir = self::cwd();
        $path = self::located($ctx, $config, $dir) ?? throw Fail::usage("no platform.toml at or above {$dir}: run lingara apps create, or lingara apps pin");
        return [self::loadAt($ctx, $path, $dir), $dir];
    }

    /**
     * The row named (by `name` or `id`), or with none named: the only row,
     * or the one whose project contains `$dir` (the deepest, when they nest).
     *
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @return array<string, mixed>
     */
    public static function resolve(array $toml, ?string $wanted, string $dir): array
    {
        $unknown = static fn (string $value): Fail => Fail::message(ExitCode::Usage, 'app_row_unknown', ['file' => PlatformToml::shown($toml['file'], $dir), 'value' => $value]);
        if ($wanted !== null) {
            foreach ($toml['apps'] as $row) {
                if ($row['name'] === $wanted || $row['id'] === $wanted) {
                    return $row;
                }
            }
            throw $unknown($wanted);
        }
        if (count($toml['apps']) === 1) {
            return $toml['apps'][0];
        }
        $containing = self::containing($toml, $dir);
        if ($containing !== [] && (count($containing) === 1 || $containing[1][0] < $containing[0][0])) {
            return $containing[0][1];
        }
        if ($toml['apps'] === []) {
            throw $unknown('(no app is pinned)');
        }
        $names = implode(', ', array_column($toml['apps'], 'name'));
        throw Fail::message(ExitCode::Usage, 'app_row_ambiguous', ['file' => PlatformToml::shown($toml['file'], $dir), 'names' => $names]);
    }

    /**
     * Each row whose project contains `$dir`, deepest first, with its depth.
     *
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @return list<array{int, array<string, mixed>}>
     */
    private static function containing(array $toml, string $dir): array
    {
        $out = [];
        foreach ($toml['apps'] as $row) {
            $project = self::real(PlatformToml::projectDir($toml['file'], $row));
            if ($dir === $project || str_starts_with($dir, rtrim($project, '/') . '/')) {
                $out[] = [count(explode('/', $project)), $row];
            }
        }
        usort($out, static fn (array $a, array $b): int => $b[0] <=> $a[0]);
        return $out;
    }
}
