<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\TableRender;
use Lingara\Cli\Dev\DevApi;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/**
 * The local verbs (ADR 16.10.26ae D4, D9): `apps status` reads the dev
 * version; `apps pin`, `apps unpin` and `apps list --pinned` edit or read
 * `platform.toml` alone (pin's one read is `app.get` for an id).
 */
final class Local
{
    /** D9's three lines. The lease time is `HH:MM` of the server's UTC stamp. */
    public static function statusLine(mixed $view): string
    {
        $url = Json::get($view, 'render_url');
        if (!is_string($url)) {
            return 'dev: serving version';
        }
        $session = Json::get($view, 'session');
        if (Json::get($session, 'state') === 'live') {
            $at = Json::opt($session, 'lease_expires_at');
            $lease = $at === null ? '?' : substr($at, 11, 5);
            return "dev: live {$url} (lease until {$lease} UTC)";
        }
        return "dev: parked (last {$url})";
    }

    /** `apps status`: live with its lease, parked with its last URL, or the serving version; `--json` is the `GET …/dev` body as the server answered. */
    public static function status(Ctx $ctx, ?string $wanted, ?string $config): void
    {
        [$toml, $cwd] = Rows::open($ctx, $config);
        $row = Rows::resolve($toml, $wanted, $cwd);
        if ($row['id'] === null) {
            throw Fail::usage("{$row['name']} is not registered yet: run lingara apps update {$row['name']}");
        }
        $view = DevApi::get($ctx, (string) $row['id']);
        $ctx->out->json ? $ctx->out->jsonValue($view) : $ctx->out->print(self::statusLine($view) . "\n");
    }

    /** A platform object's name as a local handle: `Luba Dive Log` → `luba-dive-log`. */
    private static function handleOf(string $name): string
    {
        $words = array_filter(explode('-', strtolower((string) preg_replace('/[^A-Za-z0-9]/', '-', $name))), static fn (string $w): bool => $w !== '');
        return implode('-', $words);
    }

    /** `$project` relative to `$base`, `/`-separated, or absolute when outside it. */
    private static function relativePath(string $project, string $base): string
    {
        if ($project === $base) {
            return '.';
        }
        return str_starts_with($project, "{$base}/") ? substr($project, strlen($base) + 1) : $project;
    }

    /** `apps pin <id|name> [--path <dir>]`: a row in platform.toml and, when it has none of that name, in the example. Never a platform write. */
    public static function pin(Ctx $ctx, string $app, ?string $path, ?string $config): void
    {
        $cwd = Rows::cwd();
        [$name, $id] = [$app, null];
        if (preg_match('/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/', $app) === 1) {
            [$name, $id] = [self::handleOf(Json::str(Objects::get($ctx, Kind::app(), $app), 'name')), $app];
        } elseif ($path === null) {
            throw Fail::usage("pinning {$app} by name needs --path <dir>");
        }
        if (!Schema::appNameOk($name)) {
            throw Fail::message(ExitCode::Usage, 'app_name_invalid', ['name' => $name]);
        }
        $found = Rows::located($ctx, $config, $cwd);
        $file = $found ?? "{$cwd}/" . Write::FILE;
        $names = is_file($file) ? array_column(Rows::loadAt($ctx, $file, $cwd)['apps'], 'name') : [];
        if (in_array($name, $names, true)) {
            throw Fail::usage(PlatformToml::shown($file, $cwd) . " already pins {$name}");
        }
        $project = Rows::real(PlatformToml::join($cwd, $path ?? '.'));
        Write::pinRow($file, ['name' => $name, 'path' => self::relativePath($project, Rows::real(dirname($file))), 'port' => PlatformToml::DEFAULT_PORT], $id);
        $ctx->out->nothing();
    }

    /** `apps unpin <name>`: platform.toml only; the shared example changes by review. */
    public static function unpin(Ctx $ctx, string $name, ?string $config): void
    {
        [$toml, $cwd] = Rows::open($ctx, $config);
        if (!Write::removeRow($toml['file'], $name)) {
            throw Fail::message(ExitCode::Usage, 'app_row_unknown', ['file' => PlatformToml::shown($toml['file'], $cwd), 'value' => $name]);
        }
        $ctx->out->nothing();
    }

    /** `apps list --pinned`: the rows, as JSON or as a NAME/ID/PATH/PORT table. */
    public static function listPinned(Ctx $ctx, ?string $config): void
    {
        [$toml] = Rows::open($ctx, $config);
        $rows = array_map(static fn (array $r): array => ['name' => $r['name'], 'id' => $r['id'], 'path' => $r['path'], 'port' => $r['port']], $toml['apps']);
        if ($ctx->out->json) {
            $ctx->out->jsonValue($rows);
            return;
        }
        $cells = array_map(static fn (array $r): array => [(string) $r['name'], TableRender::cell($r['id']), (string) $r['path'], (string) $r['port']], $rows);
        $ctx->out->print(TableRender::render(['NAME', 'ID', 'PATH', 'PORT'], $cells));
    }
}
