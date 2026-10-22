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

/** `lingara webhook inbound …` (ADR 16.10.26ad D1, D2). */
final class WebhookInboundHandlers
{
    /**
     * A confidential client (its body never names `events:write`), its
     * secret, then the `webhook_in` object. No `--event` omits
     * `event_types`, which 16.10.26v §5 projects as every inbound type.
     */
    public static function create(Ctx $ctx, Input $in): void
    {
        [$name, $events] = [$in->str('name'), $in->many('event')];
        $made = new Creation();
        $hook = $made->run($ctx, static function () use ($ctx, $made, $name, $events): array {
            $client = Json::str($made->object($ctx, Kind::oauthClient(), $name, null, ['client_type' => 'confidential', 'allowed_scopes' => []]), 'id');
            $made->mint($ctx, Kind::oauthClient(), $client);
            $body = $events === [] ? new \stdClass() : ['event_types' => $events];
            return [Kind::webhookInbound(), Json::str($made->object($ctx, Kind::webhookInbound(), $name, $client, $body), 'id')];
        });
        $ingest = rtrim($ctx->app->http->api, '/') . '/v1/events';
        $client = Json::str($hook, 'client_id') ?: '-';
        $ctx->out->note("Senders POST to {$ingest} as client {$client}, with its secret and an Idempotency-Key header.");
    }

    public static function list(Ctx $ctx, Input $in): void
    {
        Objects::printList($ctx, Kind::webhookInbound(), [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['EVENTS', 'event_types']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        Objects::printGet($ctx, Kind::webhookInbound(), $in->str('id'));
    }

    /** `--name` renames; `--event` replaces the event types in a body update. */
    public static function update(Ctx $ctx, Input $in): void
    {
        [$id, $events] = [$in->str('id'), $in->many('event')];
        $last = null;
        if ($in->opt('name') !== null) {
            $last = Objects::rename($ctx, Kind::webhookInbound(), $id, $in->str('name'));
        }
        if ($events !== []) {
            $last = Objects::bodyUpdate($ctx, Kind::webhookInbound(), $id, static function (\stdClass $body) use ($events): void {
                $body->event_types = $events;
            });
        }
        $ctx->out->value($last ?? throw Fail::usage('nothing to update: pass --name or --event'));
    }

    public static function delete(Ctx $ctx, Input $in): void
    {
        Deletes::delete($ctx, Kind::webhookInbound(), $in, static fn (mixed $o): string => Assets::message('consequence_webhook_inbound', ['client' => Deletes::parent($o)]));
    }
}
