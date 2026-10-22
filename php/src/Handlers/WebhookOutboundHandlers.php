<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Confirm;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\Prompt;
use Lingara\Cli\Http\Request;
use Lingara\Cli\Http\Routes;
use Lingara\Cli\Platform\Creation;
use Lingara\Cli\Platform\Deletes;
use Lingara\Cli\Platform\Kind;
use Lingara\Cli\Platform\Objects;

/** `lingara webhook outbound …` (ADR 16.10.26ad D1, D2, D5), with `replace` and `test`. */
final class WebhookOutboundHandlers
{
    /**
     * Create, publish, mint the signing secret (revealed once), read. The
     * verb takes no name, so the object is named by its URL, cut to the 80
     * characters a platform object name may hold.
     *
     * @param list<string> $events
     */
    public static function createOutbound(Ctx $ctx, string $client, string $url, array $events): mixed
    {
        $k = Kind::webhookOutbound();
        $made = new Creation();
        return $made->run($ctx, static function () use ($ctx, $made, $k, $client, $url, $events): array {
            $hook = $made->object($ctx, $k, mb_substr($url, 0, 80), $client, ['url' => $url, 'event_types' => $events]);
            $made->mint($ctx, $k, Json::str($hook, 'id'));
            return [$k, Json::str($hook, 'id')];
        });
    }

    public static function create(Ctx $ctx, Input $in): void
    {
        self::createOutbound($ctx, $in->str('client'), $in->str('url'), $in->many('event'));
    }

    public static function list(Ctx $ctx, Input $in): void
    {
        Objects::printList($ctx, Kind::webhookOutbound(), [['ID', 'id'], ['CLIENT', 'parent_id'], ['URL', 'url'], ['EVENTS', 'event_types'], ['ENABLED', 'enabled']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        Objects::printGet($ctx, Kind::webhookOutbound(), $in->str('id'));
    }

    private static function action(Ctx $ctx, string $op, string $id): mixed
    {
        return $ctx->call(new Request(Routes::object(Kind::webhookOutbound()->word, $op), ['id' => $id]));
    }

    /**
     * `--url`/`--event` are a body update that keeps the endpoint and its
     * signing secret; `--enable`/`--disable` follow it on the object-level
     * route, never the version body.
     */
    public static function update(Ctx $ctx, Input $in): void
    {
        [$id, $url, $events] = [$in->str('id'), $in->opt('url'), $in->many('event')];
        $last = null;
        if ($url !== null || $events !== []) {
            $last = Objects::bodyUpdate($ctx, Kind::webhookOutbound(), $id, static function (\stdClass $body) use ($url, $events): void {
                if ($url !== null) {
                    $body->url = $url;
                }
                if ($events !== []) {
                    $body->event_types = $events;
                }
            });
        }
        if ($in->flag('enable') || $in->flag('disable')) {
            $last = self::action($ctx, $in->flag('enable') ? 'enable' : 'disable', $id);
        }
        $ctx->out->value($last ?? throw Fail::usage('nothing to update: pass --url, --event, --enable or --disable'));
    }

    public static function test(Ctx $ctx, Input $in): void
    {
        $ctx->out->value(self::action($ctx, 'test', $in->str('id')));
    }

    public static function delete(Ctx $ctx, Input $in): void
    {
        Deletes::delete($ctx, Kind::webhookOutbound(), $in, static fn (): string => Assets::message('consequence_webhook_outbound'));
    }

    /** @return array{mixed, mixed} the old object and its serving version's body: where the new endpoint's client and any omitted flag come from */
    private static function lookups(Ctx $ctx, string $id): array
    {
        $old = Objects::get($ctx, Kind::webhookOutbound(), $id);
        $semver = Json::str($old, 'serving_version') ?: Json::str(Json::items($old, 'versions')[0] ?? null, 'semver');
        $version = Objects::versionOp($ctx, Kind::webhookOutbound(), 'version_get', $id, $semver);
        return [$old, Json::object(Json::get($version, 'body'))];
    }

    /**
     * A new endpoint with a new signing secret, then the old one deleted.
     * Both prompts, or the `--confirm` check, complete **before** the new
     * endpoint's first write. If the create lands and the delete is refused,
     * the new secret is still revealed and both ids named.
     */
    public static function replace(Ctx $ctx, Input $in): void
    {
        $id = $in->str('id');
        $looked = null;
        $read = static function () use ($ctx, $id, &$looked): Prompt {
            $looked = self::lookups($ctx, $id);
            return Deletes::prompt(Kind::webhookOutbound(), $looked[0], $id, null, static fn (): string => Assets::message('consequence_replace'));
        };
        $email = Confirm::destructive($ctx, $in->confirm(), $read);
        [$old, $body] = $looked ?? self::lookups($ctx, $id);
        $events = $in->many('event') ?: Json::strings(Json::get($body, 'event_types'));
        $created = self::createOutbound($ctx, Json::str($old, 'parent_id'), $in->opt('url') ?? Json::str($body, 'url'), $events);
        try {
            Objects::destroy($ctx, Kind::webhookOutbound(), $id, null, $email);
        } catch (Fail $fail) {
            $fail->made = [['kind' => 'webhook_outbound', 'id' => Json::str($created, 'id')]];
            throw $fail->hint("the old endpoint {$id} still delivers; delete it with `lingara webhook outbound delete {$id}`");
        }
    }
}
