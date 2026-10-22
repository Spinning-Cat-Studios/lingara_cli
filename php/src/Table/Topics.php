<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

/**
 * The topics (16.10.26ab D2): symfony/console has no topic model, so a
 * command's space-separated name is its path and each topic's summary and
 * aliases are here. `client` is `oauth_client`'s alias, registered as an
 * alias of every command under it so the resolver and `find()` see it.
 */
final class Topics
{
    public const TOPICS = [
        'apps' => ['help' => 'Apps that render cards inside Lingara.'],
        'apps versions' => ['help' => "An app's versions."],
        'embed' => ['help' => 'Lingara inside your game or website: an embed and the metered OAuth client behind it.'],
        'oauth_client' => ['help' => 'OAuth clients: the credentials your servers, apps and CI use.', 'aliases' => ['client']],
        'oauth_client secret' => ['help' => "A client's secrets. Rotate by creating the new one, deploying it, then deleting the old one."],
        'webhook' => ['help' => 'Webhooks: events your game sends in, and events Lingara sends out.'],
        'webhook inbound' => ['help' => 'Inbound webhooks: a confidential client a sender uses for POST /v1/events.'],
        'webhook outbound' => ['help' => 'Outbound webhooks: events Lingara delivers to your endpoint, signed.'],
        'canvas' => ['help' => 'Canvases: builder flows that create their objects only when applied.'],
    ];

    /** @return list<string> the topic's aliases */
    public static function aliases(string $topic): array
    {
        return self::TOPICS[$topic]['aliases'] ?? [];
    }

    /** `client secret create` as the canonical `oauth_client secret create`, or null when no alias applies. */
    public static function canonical(string $path): ?string
    {
        foreach (self::TOPICS as $topic => $row) {
            foreach ($row['aliases'] ?? [] as $alias) {
                if ($path === $alias || str_starts_with($path, $alias . ' ')) {
                    return $topic . substr($path, strlen($alias));
                }
            }
        }
        return null;
    }
}
