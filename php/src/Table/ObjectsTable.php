<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

use Lingara\Cli\Handlers\EmbedHandlers;
use Lingara\Cli\Handlers\OauthClientHandlers;

/** `oauth_client …` (alias `client`) and `embed …` (ADR 16.10.26ad D1). */
final class ObjectsTable
{
    private const SCOPE = ['name' => 'scope', 'type' => 'string', 'repeatable' => true, 'value' => 'scope'];
    private const URI = ['name' => 'redirect-uri', 'type' => 'string', 'repeatable' => true, 'value' => 'uri'];
    private const NAME = ['name' => 'name', 'type' => 'string', 'help' => 'The new name'];

    public const COMMANDS = [
        'oauth_client create' => [
            'help' => "Create an OAuth client; a confidential one's secret is shown once.",
            'run' => [OauthClientHandlers::class, 'create'],
            'flags' => [
                ['name' => 'name', 'type' => 'string', 'required' => true, 'help' => "The client's name"],
                [...self::SCOPE, 'help' => 'A scope the client may ask for'],
                ['name' => 'type', 'type' => 'enum', 'values' => ['confidential', 'public'], 'help' => 'Whether the client keeps a secret'],
                ['name' => 'billing-mode', 'type' => 'enum', 'values' => ['allowance', 'metered'], 'value' => 'mode', 'help' => 'How its usage is billed'],
                [...self::URI, 'help' => 'A redirect URI it may use'],
                ['name' => 'ci', 'type' => 'bool', 'help' => 'A CI client: confidential, holding the four platform and canvas scopes'],
            ],
            'conflicts' => [['ci', 'type'], ['ci', 'scope']],
        ],
        'oauth_client list' => ['help' => 'List your OAuth clients.', 'run' => [OauthClientHandlers::class, 'list']],
        'oauth_client get' => ['help' => 'Show one OAuth client.', 'run' => [OauthClientHandlers::class, 'get'], 'args' => Flags::ID],
        'oauth_client update' => [
            'help' => 'Rename an OAuth client, or replace its scopes, API version or redirect URIs.',
            'run' => [OauthClientHandlers::class, 'update'],
            'args' => Flags::ID,
            'flags' => [
                self::NAME,
                [...self::SCOPE, 'help' => 'Replaces the scopes'],
                ['name' => 'api-version', 'type' => 'string', 'value' => 'version', 'help' => 'The API version it is pinned to'],
                [...self::URI, 'help' => 'Replaces the redirect URIs'],
            ],
        ],
        'oauth_client delete' => [
            'help' => 'Delete an OAuth client and everything under it, or one version.',
            'run' => [OauthClientHandlers::class, 'delete'],
            'args' => Flags::ID,
            'flags' => Flags::DELETE,
        ],
        'oauth_client secret create' => [
            'help' => 'Mint a new secret, shown once. The old one stays live.',
            'run' => [OauthClientHandlers::class, 'secretCreate'],
            'args' => [['name' => 'client_id', 'type' => 'string', 'required' => true]],
        ],
        'oauth_client secret delete' => [
            'help' => 'Revoke a secret.',
            'run' => [OauthClientHandlers::class, 'secretDelete'],
            'args' => [['name' => 'client_id', 'type' => 'string', 'required' => true], ['name' => 'secret_id', 'type' => 'string', 'required' => true]],
            'flags' => Flags::CONFIRM,
        ],
        'embed create' => [
            'help' => "Create an embed and its metered OAuth client, and show the client's secret once.",
            'run' => [EmbedHandlers::class, 'create'],
            'flags' => [
                ['name' => 'name', 'type' => 'string', 'required' => true, 'help' => "The embed's name"],
                ['name' => 'origin', 'type' => 'string', 'repeatable' => true, 'value' => 'origin', 'help' => 'A web origin allowed to use it (up to 10)'],
                [...self::SCOPE, 'help' => 'A scope beside embed:mint'],
            ],
        ],
        'embed list' => ['help' => 'List your embeds.', 'run' => [EmbedHandlers::class, 'list']],
        'embed get' => ['help' => 'Show one embed.', 'run' => [EmbedHandlers::class, 'get'], 'args' => Flags::ID],
        'embed update' => [
            'help' => 'Rename an embed, or replace its origins.',
            'run' => [EmbedHandlers::class, 'update'],
            'args' => Flags::ID,
            'flags' => [self::NAME, ['name' => 'origin', 'type' => 'string', 'repeatable' => true, 'value' => 'origin', 'help' => 'Replaces the allowed origins']],
        ],
        'embed delete' => [
            'help' => 'Delete an embed and its OAuth client, or one version.',
            'run' => [EmbedHandlers::class, 'delete'],
            'args' => Flags::ID,
            'flags' => Flags::DELETE,
        ],
    ];
}
