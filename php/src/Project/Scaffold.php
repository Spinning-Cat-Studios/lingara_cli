<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * `lingara apps create <name>` without `--manifest` (ADR 16.10.26ae D1):
 * scaffold offline, pin the project in `platform.toml`, then register it as
 * a private app unless `--no-register` or there is no session.
 */
final class Scaffold
{
    private const DEFAULT_DESCRIPTION = 'A Lingara app.';

    /** The description goes inside a string literal in every template language. */
    private static function descriptionOk(string $text): bool
    {
        return preg_match('/["\\\\`$#{}]/', $text) !== 1 && preg_match('/\p{Cc}/u', $text) !== 1;
    }

    /** @return array{name: string, display_name: string, description: string, port: int, slots: list<string>, context: list<string>} */
    private static function keys(Input $in): array
    {
        $name = $in->str('name');
        if (!Schema::appNameOk($name)) {
            throw Fail::message(ExitCode::Usage, 'app_name_invalid', ['name' => $name]);
        }
        $description = $in->opt('description') ?? self::DEFAULT_DESCRIPTION;
        if (!self::descriptionOk($description)) {
            throw Fail::usage('--description may not hold ", \\, `, $, #, { or }, or a control character');
        }
        foreach ($in->many('context') as $context) {
            if (preg_match('/^[a-z][a-z_]*$/', $context) !== 1) {
                throw Fail::usage("--context {$context} is not a context slice name");
            }
        }
        $slots = $in->many('slot') ?: ['home.side'];
        $port = $in->int('port') ?? PlatformToml::DEFAULT_PORT;
        return ['name' => $name, 'display_name' => ProjectManifest::displayName($name), 'description' => $description, 'port' => $port, 'slots' => $slots, 'context' => $in->many('context')];
    }

    /** `$dir` relative to `$base`, `/`-separated; `.` when they are one. */
    private static function relativeTo(string $dir, string $base): string
    {
        if ($dir === $base) {
            return '.';
        }
        return str_starts_with($dir, rtrim($base, '/') . '/') ? substr($dir, strlen(rtrim($base, '/')) + 1) : $dir;
    }

    /** Step 3's file: `--config` or `LINGARA_PLATFORM_TOML`, else the nearest above the project's parent, else a new one inside the project. */
    private static function pinFile(Ctx $ctx, Input $in, string $dir, string $cwd): string
    {
        $explicit = $in->opt('config') !== null || $ctx->app->env->get('LINGARA_PLATFORM_TOML') !== null ? Rows::located($ctx, $in->opt('config'), $cwd) : null;
        $found = $explicit ?? PlatformToml::findUpward(dirname($dir));
        if ($found === null || !is_file($found)) {
            return "{$dir}/" . Write::FILE;
        }
        foreach (Rows::loadAt($ctx, $found, $cwd)['apps'] as $row) {
            if ($row['name'] === $in->str('name')) {
                throw Fail::usage(PlatformToml::shown($found, $cwd) . " already pins {$in->str('name')}");
            }
        }
        return $found;
    }

    /** Step 2, last: offline when `--no-register`, or with no session to use. */
    private static function registerIt(Ctx $ctx, string $name, string $file, string $cwd): void
    {
        if ($ctx->ciPair() === null && $ctx->app->stores->load($ctx->profile) === null) {
            $ctx->out->note(Assets::message('apps_create_login_hint', ['name' => $name]));
            $ctx->out->nothing();
            return;
        }
        $toml = Rows::loadAt($ctx, $file, $cwd);
        $row = Rows::resolve($toml, $name, $cwd);
        $manifest = ProjectManifest::read($ctx, $toml, $row, $cwd);
        ProjectManifest::warn($ctx, $manifest);
        ProjectManifest::register($ctx, $toml, $row, $manifest);
    }

    public static function run(Ctx $ctx, Input $in): void
    {
        $keys = self::keys($in);
        $cwd = Rows::cwd();
        $wanted = $in->opt('dir') === null ? "{$cwd}/{$keys['name']}" : PlatformToml::join($cwd, (string) $in->opt('dir'));
        if (is_dir($wanted) && array_diff((array) scandir($wanted), ['.', '..']) !== []) {
            throw Fail::message(ExitCode::Usage, 'dir_not_empty', ['path' => PlatformToml::shown($wanted, $cwd)]);
        }
        $lang = $in->opt('lang') ?? 'typescript';
        [$files, $meta] = Template::render($lang, $in->flag('bare') ? 'bare' : 'default', $keys);
        $file = self::pinFile($ctx, $in, $wanted, $cwd);
        if (!is_dir($wanted)) {
            mkdir($wanted, 0777, true);
        }
        $dir = Rows::real($wanted);
        Template::writeAll($dir, $files);
        $base = Rows::real(dirname($file));
        $target = $base . '/' . basename($file);
        Write::pinRow($target, ['name' => $keys['name'], 'path' => self::relativeTo($dir, $base), 'port' => $keys['port'], 'start' => $meta['start'], 'manifest' => $meta['manifest']], null);
        Write::ensureGitignore($base);
        if ($in->flag('no-register')) {
            $ctx->out->nothing();
            return;
        }
        self::registerIt($ctx, $keys['name'], $target, $cwd);
    }
}
