<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Canvas\Apply;
use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Confirm;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\Prompt;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Platform\Deletes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/** `lingara canvas …` (ADR 16.10.26ad D1, D7): content through `/v1/canvases`, lifecycle through `/platform/objects`. */
final class CanvasHandlers
{
    /** A definition file's JSON; an unreadable or invalid file is `usage`. */
    public static function definitionFile(string $path): mixed
    {
        $text = @file_get_contents($path);
        if ($text === false) {
            throw Fail::usage("{$path}: the file cannot be read");
        }
        return Json::isJson($text) ? Json::decode($text) : throw Fail::usage("{$path}: " . json_last_error_msg());
    }

    /** A `/v1/canvases` 404 while the builder flag is off (16.10.26x D3), where no id could be wrong. */
    private static function builderHint(Fail $fail): Fail
    {
        return $fail->exit === ExitCode::NotFound ? $fail->hint('the canvas builder may not be enabled for this account') : $fail;
    }

    /** `canvas.create` answers the canvas itself, so there is no final read. */
    public static function create(Ctx $ctx, Input $in): void
    {
        $file = $in->opt('file');
        $definition = $file === null ? ['schema' => 2, 'nodes' => [], 'edges' => []] : self::definitionFile($file);
        $body = $in->opt('name') === null ? ['definition' => $definition] : ['name' => $in->str('name'), 'definition' => $definition];
        try {
            $ctx->out->value($ctx->call(Request::json(Routes::fixed('canvas.create'), [], $body)));
        } catch (Fail $fail) {
            throw self::builderHint($fail);
        }
    }

    public static function list(Ctx $ctx, Input $in): void
    {
        $route = Routes::fixed('canvas.list');
        try {
            $items = Objects::pages(static fn (?string $cursor): mixed => $ctx->call(new Request($route, [], $cursor === null ? [] : [['cursor', $cursor]])));
        } catch (Fail $fail) {
            throw self::builderHint($fail);
        }
        $ctx->out->list($items, [['ID', 'id'], ['NAME', 'name'], ['DRAFT', 'has_draft'], ['ACTIVE', 'active_revision'], ['UPDATED', 'updated_at']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        $ctx->out->value(Apply::getCanvas($ctx, $in->str('id')));
    }

    public static function update(Ctx $ctx, Input $in): void
    {
        $ctx->out->value($ctx->call(Request::json(Routes::fixed('canvas.rename'), ['id' => $in->str('id')], ['name' => $in->str('name')])));
    }

    /** Saves the definition at the draft's `draft_version` (`0`: a new draft). It creates nothing else; that is `apply`'s alone (B7). */
    public static function draft(Ctx $ctx, Input $in): void
    {
        [$id, $definition] = [$in->str('id'), self::definitionFile($in->str('file'))];
        $version = Json::get(Json::get(Apply::getCanvas($ctx, $id), 'draft'), 'draft_version');
        $body = ['definition' => $definition, 'draft_version' => is_int($version) ? $version : 0];
        $ctx->out->value($ctx->call(Request::json(Routes::fixed('canvas.put_draft'), ['id' => $id], $body)));
    }

    public static function apply(Ctx $ctx, Input $in): void
    {
        Apply::apply($ctx, $in->str('id'), $in->flag('dry-run'), $in->flag('print-commands'));
    }

    /** The `canvas` object behind a `/v1/canvases` id (16.10.26v §8). */
    private static function lookup(Ctx $ctx, string $canvasId): string
    {
        $page = $ctx->call(new Request(Routes::fixed('canvas.lookup'), [], [['kind', 'canvas'], ['runtime_id', $canvasId]]));
        $found = Json::str(Json::items($page, 'items')[0] ?? null, 'id');
        return $found !== '' ? $found : throw new Fail(ExitCode::NotFound, 'object_not_found', "no canvas {$canvasId}");
    }

    /**
     * `canvas.lookup` turns the builder's id into the platform object's; the
     * prompt reads that object; the delete is `/platform/objects`', with the
     * typed email, as for every other kind.
     */
    public static function delete(Ctx $ctx, Input $in): void
    {
        [$id, $version] = [$in->str('id'), $in->opt('version')];
        $platformId = null;
        $read = static function () use ($ctx, $id, $version, &$platformId): Prompt {
            $platformId = self::lookup($ctx, $id);
            $obj = $ctx->call(new Request(Routes::objectNamed('canvas.object_get', 'get'), ['id' => $platformId]));
            return Deletes::prompt(Kind::canvas(), $obj, $id, $version, static fn (): string => Assets::message('consequence_canvas'));
        };
        $email = Confirm::destructive($ctx, $in->confirm(), $read);
        Objects::destroy($ctx, Kind::canvas(), $platformId ?? self::lookup($ctx, $id), $version, $email);
        $ctx->out->nothing();
    }
}
