<?php

declare(strict_types=1);

namespace Lingara\Cli\Platform;

use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;

/**
 * The `/platform/objects` family every kind shares (16.10.26v §8; ADR
 * 16.10.26ad D2): list every page, read, rename, create and publish, the
 * body update that respects the one editable version, delete with the typed
 * email, and the one-time secret mint.
 */
final class Objects
{
    /** @param array<string, string> $vars */
    private static function send(Ctx $ctx, Kind $k, string $op, array $vars, mixed $body = null): mixed
    {
        return $ctx->call(Request::json(Routes::object($k->word, $op), $vars, $body));
    }

    /**
     * Every item of every page, followed by `next_cursor` (B1).
     *
     * @param \Closure(?string): mixed $fetch one page at a cursor
     * @return list<mixed>
     */
    public static function pages(\Closure $fetch): array
    {
        $items = [];
        $cursor = null;
        do {
            $page = $fetch($cursor);
            array_push($items, ...Json::items($page, 'items'));
            $cursor = Json::opt($page, 'next_cursor');
        } while ($cursor !== null);
        return $items;
    }

    /** @return list<mixed> */
    public static function list(Ctx $ctx, Kind $k): array
    {
        $route = Routes::object($k->word, 'list');
        return self::pages(static function (?string $cursor) use ($ctx, $k, $route): mixed {
            $query = [['kind', $k->kind]];
            if ($cursor !== null) {
                $query[] = ['cursor', $cursor];
            }
            return $ctx->call(new Request($route, [], $query));
        });
    }

    public static function get(Ctx $ctx, Kind $k, string $id): mixed
    {
        return self::send($ctx, $k, 'get', ['id' => $id]);
    }

    public static function rename(Ctx $ctx, Kind $k, string $id, string $name): mixed
    {
        return self::send($ctx, $k, 'rename', ['id' => $id], ['name' => $name]);
    }

    public static function versionOp(Ctx $ctx, Kind $k, string $op, string $id, string $semver): mixed
    {
        return self::send($ctx, $k, $op, ['id' => $id, 'semver' => $semver]);
    }

    public static function secretCreate(Ctx $ctx, Kind $k, string $id): mixed
    {
        return self::send($ctx, $k, 'secret_create', ['id' => $id]);
    }

    /** The object and its first draft: `{kind, name, parent_id?, body}`. */
    public static function create(Ctx $ctx, Kind $k, string $name, ?string $parent, mixed $body): mixed
    {
        $request = ['kind' => $k->kind, 'name' => $name, 'body' => $body];
        if ($parent !== null) {
            $request['parent_id'] = $parent;
        }
        return self::send($ctx, $k, 'create', [], $request);
    }

    /** A private object skips review, so a version is published at once (16.10.26v §4). */
    public static function publishIfPrivate(Ctx $ctx, Kind $k, mixed $obj, string $semver): mixed
    {
        if (Json::get($obj, 'visibility') === 'public' || $semver === '') {
            return null;
        }
        return self::versionOp($ctx, $k, 'publish', Json::str($obj, 'id'), $semver);
    }

    /**
     * D2's body update: overlay the flags on the one editable version
     * (`version_put` at its `draft_version`), else on the serving (or
     * highest) version as a new one (`version_create`). Always a whole body.
     *
     * @param \Closure(\stdClass): void $overlay
     */
    public static function bodyUpdate(Ctx $ctx, Kind $k, string $id, \Closure $overlay): mixed
    {
        $current = self::get($ctx, $k, $id);
        $versions = Json::items($current, 'versions');
        $editable = null;
        foreach ($versions as $v) {
            if (in_array(Json::get($v, 'state'), ['draft', 'changes_requested'], true)) {
                $editable = $v;
                break;
            }
        }
        if ($editable !== null) {
            $semver = Json::str($editable, 'semver');
            $version = self::versionOp($ctx, $k, 'version_get', $id, $semver);
            $edit = ['body' => self::overlaid(Json::get($version, 'body'), $overlay), 'draft_version' => Json::get($version, 'draft_version')];
            $answer = self::send($ctx, $k, 'version_put', ['id' => $id, 'semver' => $semver], $edit);
        } else {
            $base = Json::str($current, 'serving_version') ?: Json::str($versions[0] ?? null, 'semver');
            $start = $base === '' ? null : Json::get(self::versionOp($ctx, $k, 'version_get', $id, $base), 'body');
            $answer = self::send($ctx, $k, 'version_create', ['id' => $id], ['body' => self::overlaid($start, $overlay)]);
        }
        return self::publishIfPrivate($ctx, $k, $current, Json::str($answer, 'semver')) ?? $answer;
    }

    /** @param \Closure(\stdClass): void $overlay */
    private static function overlaid(mixed $body, \Closure $overlay): \stdClass
    {
        $out = $body instanceof \stdClass ? clone $body : new \stdClass();
        $overlay($out);
        return $out;
    }

    /** The destructive request: the canonical email in its body (B3.6). */
    public static function destroy(Ctx $ctx, Kind $k, string $id, ?string $version, string $email): mixed
    {
        $body = ['confirm' => $email];
        return $version === null
            ? self::send($ctx, $k, 'delete', ['id' => $id], $body)
            : self::send($ctx, $k, 'delete_version', ['id' => $id, 'semver' => $version], $body);
    }

    /** @param list<array{string, string}> $columns every page of `$k`, printed: one JSON array, or the schema's columns */
    public static function printList(Ctx $ctx, Kind $k, array $columns): void
    {
        $ctx->out->list(self::list($ctx, $k), $columns);
    }

    public static function printGet(Ctx $ctx, Kind $k, string $id): void
    {
        $ctx->out->value(self::get($ctx, $k, $id));
    }
}
