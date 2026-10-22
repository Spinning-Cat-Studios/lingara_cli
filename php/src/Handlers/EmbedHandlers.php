<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Platform\Creation;
use Lingara\Cli\Platform\Deletes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/** `lingara embed …` (ADR 16.10.26ad D1, D2): an embed and the metered client behind it. */
final class EmbedHandlers
{
    /** The metered, confidential client first (its body never names `embed:mint`, the embed's projection adds it), its secret, then the embed. */
    public static function create(Ctx $ctx, Input $in): void
    {
        $name = $in->str('name');
        $made = new Creation();
        $made->run($ctx, static function () use ($ctx, $made, $in, $name): array {
            $body = ['client_type' => 'confidential', 'billing_mode' => 'metered', 'allowed_scopes' => $in->many('scope')];
            $client = Json::str($made->object($ctx, Kind::oauthClient(), $name, null, $body), 'id');
            $made->mint($ctx, Kind::oauthClient(), $client);
            $embed = $made->object($ctx, Kind::embed(), $name, $client, ['origins' => $in->many('origin')]);
            return [Kind::embed(), Json::str($embed, 'id')];
        });
    }

    public static function list(Ctx $ctx, Input $in): void
    {
        Objects::printList($ctx, Kind::embed(), [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['ORIGINS', 'origins'], ['SERVING', 'serving_version']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        Objects::printGet($ctx, Kind::embed(), $in->str('id'));
    }

    /** `--name` renames; `--origin` replaces the origins in a body update. */
    public static function update(Ctx $ctx, Input $in): void
    {
        [$id, $origins] = [$in->str('id'), $in->many('origin')];
        $last = null;
        if ($in->opt('name') !== null) {
            $last = Objects::rename($ctx, Kind::embed(), $id, $in->str('name'));
        }
        if ($origins !== []) {
            $last = Objects::bodyUpdate($ctx, Kind::embed(), $id, static function (\stdClass $body) use ($origins): void {
                $body->origins = $origins;
            });
        }
        $ctx->out->value($last ?? throw Fail::usage('nothing to update: pass --name or --origin'));
    }

    public static function delete(Ctx $ctx, Input $in): void
    {
        Deletes::delete($ctx, Kind::embed(), $in, static fn (mixed $o): string => Assets::message('consequence_embed', ['client' => Deletes::parent($o)]));
    }
}
