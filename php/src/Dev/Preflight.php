<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Project\Git;
use Lingara\Cli\Project\PlatformToml;

/**
 * `apps dev`'s step 2 (ADR 16.10.26ae D7): every refusal before the network,
 * so a refused run sends no request, and the plan the session runs. The
 * session record is read, never refreshed; a refresh, if due, rides the
 * first request. Before any of it, this kit's own refusals (ADR 16.10.26af
 * D2): Windows is not supported by this release, and on Unix the session
 * needs ext-pcntl and ext-posix, asked through the app's `extension_loaded`
 * seam.
 */
final class Preflight
{
    public static function platform(Ctx $ctx): void
    {
        if (PHP_OS_FAMILY === 'Windows') {
            throw Fail::message(ExitCode::Failure, 'apps_dev_unsupported_platform', ['platform' => 'Windows']);
        }
        $missing = array_values(array_filter(['pcntl', 'posix'], static fn (string $ext): bool => !($ctx->app->extensionLoaded)($ext)));
        if ($missing !== []) {
            $names = implode(' and ', array_map(static fn (string $ext): string => "ext-{$ext}", $missing));
            throw Fail::message(ExitCode::ToolMissing, 'tool_missing_php_ext', ['extensions' => $names]);
        }
    }

    private static function ignoredOrRefuse(string $path, string $cwd): void
    {
        if (!Git::ignored($path)) {
            throw Fail::message(ExitCode::Usage, 'path_not_ignored', ['path' => PlatformToml::shown($path, $cwd)]);
        }
    }

    /** @return list<array{string, string}> `KEY=VALUE` lines; blank lines, `#` comments and an `export ` prefix are skipped */
    private static function envFile(string $path): array
    {
        $text = is_file($path) ? @file_get_contents($path) : false;
        if ($text === false) {
            throw Fail::usage("{$path}: the file cannot be read");
        }
        $out = [];
        foreach (explode("\n", $text) as $raw) {
            $line = (string) preg_replace('/^export /', '', trim($raw));
            $eq = strpos($line, '=');
            if (str_starts_with($line, '#') || $eq === false || $eq === 0) {
                continue;
            }
            $value = trim(substr($line, $eq + 1));
            $out[] = [trim(substr($line, 0, $eq)), preg_match('/^(["\'])(.*)\1$/', $value, $m) === 1 ? $m[2] : $value];
        }
        return $out;
    }

    /** @return array{cloudflared?: string, fixed?: string} where the dev version will point: a cloudflared it spawns, or a URL */
    private static function target(Ctx $ctx, Input $in): array
    {
        [$tunnel, $renderUrl] = [$in->opt('tunnel'), $in->opt('render-url')];
        if ($tunnel === 'none') {
            return ['fixed' => $renderUrl ?? throw Fail::usage('--tunnel none needs --render-url')];
        }
        if ($renderUrl !== null) {
            throw Fail::usage('--render-url needs --tunnel none');
        }
        return ['cloudflared' => Cloudflared::find($ctx->app->env)];
    }

    /**
     * @param array{file: string, api: ?string, profile: ?string, apps: list<array<string, mixed>>} $toml
     * @param array<string, mixed> $row
     * @return array<string, mixed> the plan `DevRun` runs
     */
    public static function plan(Ctx $ctx, Input $in, array $toml, array $row, string $cwd): array
    {
        $start = $row['start'] ?? throw Fail::usage("{$row['name']}'s row has no start command");
        $dir = PlatformToml::projectDir($toml['file'], $row);
        $env = [];
        foreach ((array) $row['env'] as $key => $value) {
            $env[] = [(string) $key, (string) $value];
        }
        if (is_string($row['env_file'])) {
            $path = PlatformToml::join($dir, $row['env_file']);
            self::ignoredOrRefuse($path, $cwd);
            array_push($env, ...self::envFile($path));
        }
        $written = $in->opt('write-env');
        $writeEnv = $written === null ? null : PlatformToml::join($cwd, $written);
        if ($writeEnv !== null) {
            self::ignoredOrRefuse($writeEnv, $cwd);
        }
        $target = self::target($ctx, $in);
        if ($ctx->ciPair() === null && $ctx->app->stores->load($ctx->profile) === null) {
            throw Fail::auth('not signed in: run `lingara login`');
        }
        return [
            ...$target, 'name' => $row['name'], 'id' => $row['id'] ?? '', 'dir' => $dir, 'port' => $in->int('port') ?? $row['port'], 'start' => $start,
            'render_path' => $row['render_path'] ?? '/', 'take_over' => $in->flag('take-over'), 'client' => in_array('client_credentials', $row['credentials'], true),
            'env' => $env, 'write_env' => $writeEnv, 'show_env' => $in->flag('show-env'), 'probe' => $ctx->app->env->get('LINGARA_TUNNEL_PROBE_URL'),
        ];
    }

    /**
     * `--reset`: the owner's install back on the serving version. A live session refuses it (`apps_dev_reset_held`).
     *
     * @param array<string, mixed> $row
     */
    public static function reset(Ctx $ctx, array $row): void
    {
        $id = $row['id'] ?? throw Fail::usage("{$row['name']} is not registered yet: run lingara apps update {$row['name']} first");
        try {
            DevApi::patchWithRetry($ctx, (string) $id, static fn (mixed $draft): array => ['render_url' => null, 'draft_version' => $draft]);
        } catch (Fail $fail) {
            throw $fail->exit === ExitCode::DevSessionHeld ? Fail::message(ExitCode::DevSessionHeld, 'apps_dev_reset_held', ['name' => (string) $row['name']]) : $fail;
        }
        $ctx->out->note(Assets::message('apps_dev_reset', ['name' => (string) $row['name']]));
        $ctx->out->nothing();
    }
}
