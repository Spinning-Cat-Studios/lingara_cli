<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Auth\Tokens;
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

/** `lingara oauth_client …`, alias `client` (ADR 16.10.26ad D1, D2, D5). */
final class OauthClientHandlers
{
    /** `--ci` is confidential with the four scopes; whether the client may be one is the server's predicate. */
    public static function create(Ctx $ctx, Input $in): void
    {
        $ci = $in->flag('ci');
        $type = $ci ? 'confidential' : ($in->opt('type') ?? 'confidential');
        $body = [
            'client_type' => $type,
            'allowed_scopes' => $ci ? explode(' ', Tokens::SCOPES) : $in->many('scope'),
            'billing_mode' => $in->opt('billing-mode') ?? 'allowance',
            'redirect_uris' => $in->many('redirect-uri'),
        ];
        $k = Kind::oauthClient();
        $made = new Creation();
        $made->run($ctx, static function () use ($ctx, $made, $k, $in, $body, $type): array {
            $id = Json::str($made->object($ctx, $k, $in->str('name'), null, $body), 'id');
            if ($type === 'confidential') {
                $made->mint($ctx, $k, $id);
            }
            return [$k, $id];
        });
    }

    public static function list(Ctx $ctx, Input $in): void
    {
        Objects::printList($ctx, Kind::oauthClient(), [['ID', 'id'], ['NAME', 'name'], ['TYPE', 'client_type'], ['SCOPES', 'allowed_scopes'], ['CREATED', 'created_at']]);
    }

    public static function get(Ctx $ctx, Input $in): void
    {
        Objects::printGet($ctx, Kind::oauthClient(), $in->str('id'));
    }

    /** `--name` is a rename; any other flag is a body update over the one editable version. */
    public static function update(Ctx $ctx, Input $in): void
    {
        [$id, $scope, $uris, $apiVersion] = [$in->str('id'), $in->many('scope'), $in->many('redirect-uri'), $in->opt('api-version')];
        $last = null;
        if ($in->opt('name') !== null) {
            $last = Objects::rename($ctx, Kind::oauthClient(), $id, $in->str('name'));
        }
        if ($scope !== [] || $uris !== [] || $apiVersion !== null) {
            $last = Objects::bodyUpdate($ctx, Kind::oauthClient(), $id, static function (\stdClass $body) use ($scope, $uris, $apiVersion): void {
                if ($scope !== []) {
                    $body->allowed_scopes = $scope;
                }
                if ($apiVersion !== null) {
                    $body->api_version = $apiVersion;
                }
                if ($uris !== []) {
                    $body->redirect_uris = $uris;
                }
            });
        }
        $ctx->out->value($last ?? throw Fail::usage('nothing to update: pass --name, --scope, --api-version or --redirect-uri'));
    }

    /** The children 16.10.26v §7's cascade deletes, counted by kind from the one read. */
    public static function delete(Ctx $ctx, Input $in): void
    {
        Deletes::delete($ctx, Kind::oauthClient(), $in, static function (mixed $obj): string {
            $kinds = array_map(static fn (mixed $c): string => Json::str($c, 'kind'), Json::items($obj, 'children'));
            $count = static fn (array $want): string => (string) count(array_intersect($kinds, $want));
            return Assets::message('consequence_oauth_client', ['apps' => $count(['app']), 'embeds' => $count(['embed']), 'webhooks' => $count(['webhook_in', 'webhook_out'])]);
        });
    }

    /** Rotation's first call; the old secret stays live. */
    public static function secretCreate(Ctx $ctx, Input $in): void
    {
        $ctx->out->value(Objects::secretCreate($ctx, Kind::oauthClient(), $in->str('client_id')));
        $ctx->out->note(Creation::SHOWN_ONCE);
    }

    /** Rotation's second call. A secret has no read of its own, so the prompt names the client's. */
    public static function secretDelete(Ctx $ctx, Input $in): void
    {
        [$clientId, $secretId] = [$in->str('client_id'), $in->str('secret_id')];
        $k = Kind::oauthClient();
        $read = static function () use ($ctx, $k, $clientId, $secretId): Prompt {
            $prompt = Deletes::prompt($k, Objects::get($ctx, $k, $clientId), $secretId, null, static fn (): string => Assets::message('consequence_secret'));
            return new Prompt('secret', $prompt->name, $prompt->id, $prompt->consequence);
        };
        $email = Confirm::destructive($ctx, $in->confirm(), $read);
        $route = Routes::object($k->word, 'secret_delete');
        $ctx->call(Request::json($route, ['id' => $clientId, 'secret_id' => $secretId], ['confirm' => $email]));
        $ctx->out->nothing();
    }
}
