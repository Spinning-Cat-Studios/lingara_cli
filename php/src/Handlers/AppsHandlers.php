<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Dev\DevApi;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Platform\Creation;
use Lingara\Cli\Platform\Deletes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;
use Lingara\Cli\Project\Local;
use Lingara\Cli\Project\ManifestRules;
use Lingara\Cli\Project\ProjectManifest;
use Lingara\Cli\Project\Rows;
use Lingara\Cli\Project\Scaffold;

/**
 * `lingara apps …` (ADR 16.10.26ad D1, D2; 16.10.26ae D1, D4, D6, D9).
 * `create` and `update` each carry both forms, the registration one
 * (`--manifest`) and the project one, with one dispatch point each.
 */
final class AppsHandlers
{
    /** `--manifest <path>` is the file's JSON as the app body's `manifest`. */
    private static function manifestFile(string $path): mixed
    {
        $text = is_file($path) ? @file_get_contents($path) : false;
        if ($text === false) {
            throw Fail::usage("{$path}: the file cannot be read");
        }
        return Json::isJson($text) ? Json::decode($text) : throw Fail::usage("{$path} is not JSON: " . json_last_error_msg());
    }

    /** With `--manifest` and `--client`, 16.10.26ad's registration form; with neither, 16.10.26ae's scaffold. */
    public static function create(Ctx $ctx, Input $in): void
    {
        [$manifest, $client] = [$in->opt('manifest'), $in->opt('client')];
        if ($manifest !== null && $client !== null) {
            $body = ['manifest' => self::manifestFile($manifest)];
            $made = new Creation();
            $made->run($ctx, static fn (): array => [Kind::app(), Json::str($made->object($ctx, Kind::app(), $in->str('name'), $client, $body), 'id')]);
            return;
        }
        if ($manifest !== null || $client !== null) {
            throw Fail::usage('--manifest and --client go together: both register an app from a file, neither scaffolds a project');
        }
        Scaffold::run($ctx, $in);
    }

    /** `--pinned` reads platform.toml instead (16.10.26ae D4). */
    public static function list(Ctx $ctx, Input $in): void
    {
        if ($in->flag('pinned')) {
            Local::listPinned($ctx, $in->opt('config'));
            return;
        }
        Objects::printList($ctx, Kind::app(), [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['SERVING', 'serving_version'], ['VISIBILITY', 'visibility']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        Objects::printGet($ctx, Kind::app(), $in->str('id'));
    }

    /**
     * One dispatch point on `--manifest`: with it, a new version from the
     * file; without it, the project's manifest goes to the dev version. A
     * quick-tunnel or `dev.invalid` render URL in the file is refused before
     * any request (D6 step 3's guard, D10 row 15).
     */
    public static function update(Ctx $ctx, Input $in): void
    {
        $path = $in->opt('manifest');
        if ($path === null) {
            self::projectUpdate($ctx, $in);
            return;
        }
        $id = $in->opt('id') ?? throw Fail::usage("apps update --manifest needs the app's id");
        $manifest = self::manifestFile($path);
        if (ManifestRules::ephemeral(Json::str($manifest, 'render_url'))) {
            throw Fail::message(ExitCode::RenderUrlEphemeral, 'render_url_ephemeral', ['file' => $path]);
        }
        $ctx->out->value(Objects::bodyUpdate($ctx, Kind::app(), $id, static function (\stdClass $body) use ($manifest): void {
            $body->manifest = $manifest;
        }));
    }

    /**
     * Without `--manifest` (16.10.26ae D6): the project's manifest goes to
     * the **dev version** (`PATCH …/dev`, never its `render_url`), or with
     * `--version` becomes an immutable draft. A row with no id is registered
     * first.
     */
    private static function projectUpdate(Ctx $ctx, Input $in): void
    {
        [$toml, $cwd] = Rows::open($ctx, $in->opt('config'));
        $row = Rows::resolve($toml, $in->opt('id'), $cwd);
        $manifest = ProjectManifest::read($ctx, $toml, $row, $cwd);
        $version = $in->opt('version');
        if ($version !== null && ManifestRules::ephemeral(Json::str($manifest, 'render_url'))) {
            throw Fail::message(ExitCode::RenderUrlEphemeral, 'render_url_ephemeral', ['file' => ProjectManifest::source($toml, $row, $cwd)]);
        }
        $codes = ProjectManifest::warn($ctx, $manifest);
        if ($row['id'] === null) {
            ProjectManifest::register($ctx, $toml, $row, $manifest);
            return;
        }
        $id = (string) $row['id'];
        $answer = $version !== null
            // Step 3: an immutable draft; the server's lifecycle rules stand, as `refused`.
            ? $ctx->call(Request::json(Routes::object('app', 'version_create'), ['id' => $id], ['semver' => $version, 'body' => ['manifest' => $manifest]]))
            : DevApi::patchWithRetry($ctx, $id, static fn (mixed $draft): array => ['manifest' => $manifest, 'draft_version' => $draft]);
        if ($ctx->out->json) {
            // The server's answer, with `warnings: [code]` under `--json`.
            $answer = Json::object($answer);
            $answer->warnings = $codes;
        }
        $ctx->out->value($answer);
    }

    public static function status(Ctx $ctx, Input $in): void
    {
        Local::status($ctx, $in->opt('name'), $in->opt('config'));
    }

    public static function pin(Ctx $ctx, Input $in): void
    {
        Local::pin($ctx, $in->str('app'), $in->opt('path'), $in->opt('config'));
    }

    public static function unpin(Ctx $ctx, Input $in): void
    {
        Local::unpin($ctx, $in->str('name'), $in->opt('config'));
    }

    public static function delete(Ctx $ctx, Input $in): void
    {
        Deletes::delete($ctx, Kind::app(), $in, static fn (mixed $o): string => Assets::message('consequence_app', ['client' => Deletes::parent($o)]));
    }

    /** `{"visibility": "public"}`; review itself is the operator's, in the portal. */
    public static function submit(Ctx $ctx, Input $in): void
    {
        $vars = ['id' => $in->str('id'), 'semver' => $in->str('version')];
        $ctx->out->value($ctx->call(Request::json(Routes::object('app', 'submit'), $vars, ['visibility' => 'public'])));
    }

    /** Every page, newest first; `next_cursor` is the last semver seen. */
    public static function versionsList(Ctx $ctx, Input $in): void
    {
        $route = Routes::object('app', 'versions');
        $items = Objects::pages(static fn (?string $cursor): mixed => $ctx->call(new Request($route, ['id' => $in->str('id')], $cursor === null ? [] : [['cursor', $cursor]])));
        $ctx->out->list($items, [['SEMVER', 'semver'], ['STATE', 'state'], ['STATE_AT', 'state_at'], ['REVIEWED', 'review_basis']]);
    }

    public static function versionsPublish(Ctx $ctx, Input $in): void
    {
        $ctx->out->value(Objects::versionOp($ctx, Kind::app(), 'publish', $in->str('id'), $in->str('semver')));
    }

    public static function versionsWithdraw(Ctx $ctx, Input $in): void
    {
        $ctx->out->value(Objects::versionOp($ctx, Kind::app(), 'withdraw', $in->str('id'), $in->str('semver')));
    }
}
