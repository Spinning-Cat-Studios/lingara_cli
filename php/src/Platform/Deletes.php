<?php

declare(strict_types=1);

namespace Lingara\Cli\Platform;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Confirm;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\Prompt;

/**
 * Every object `delete` (ADR 16.10.26ad D5): the ritual over one
 * `<word>.get` for the prompt, then the destructive request with the
 * canonical email.
 */
final class Deletes
{
    /** `{client}` is the read's `parent_id`, the client's platform object id, never the public `client_id`. */
    public static function parent(mixed $obj): string
    {
        return Json::str($obj, 'parent_id') ?: '-';
    }

    /** @param \Closure(mixed): string $consequence the kind's `consequence_*` line, from the read */
    public static function prompt(Kind $k, mixed $obj, string $id, ?string $version, \Closure $consequence): Prompt
    {
        $line = $version === null ? $consequence($obj) : Assets::message('consequence_version', ['semver' => $version]);
        $pinned = Json::get($obj, 'pinned_by');
        return new Prompt($k->label, Json::str($obj, 'name') ?: $id, $id, $line, is_int($pinned) ? $pinned : 0);
    }

    /**
     * `<id> [--version <semver>] [-y --confirm <email>]`, the shape every
     * object `delete` takes.
     *
     * @param \Closure(mixed): string $consequence
     */
    public static function delete(Ctx $ctx, Kind $k, Input $in, \Closure $consequence): void
    {
        [$id, $version] = [$in->str('id'), $in->opt('version')];
        $read = static fn (): Prompt => self::prompt($k, Objects::get($ctx, $k, $id), $id, $version, $consequence);
        $email = Confirm::destructive($ctx, $in->confirm(), $read);
        Objects::destroy($ctx, $k, $id, $version, $email);
        $ctx->out->nothing();
    }
}
