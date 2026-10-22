<?php

declare(strict_types=1);

namespace Lingara\Cli\Platform;

/** A CLI word and the 16.10.26v kind behind it (ADR 16.10.26ad D2). */
final class Kind
{
    public function __construct(
        /** `routes.toml`'s word: `webhook_outbound`. */
        public readonly string $word,
        /** The platform kind: `webhook_out`. */
        public readonly string $kind,
        /** `{kind}` in `confirm_yes_no`. */
        public readonly string $label,
    ) {
    }

    public static function oauthClient(): self
    {
        return new self('oauth_client', 'oauth_client', 'OAuth client');
    }

    public static function app(): self
    {
        return new self('app', 'app', 'app');
    }

    public static function embed(): self
    {
        return new self('embed', 'embed', 'embed');
    }

    public static function webhookInbound(): self
    {
        return new self('webhook_inbound', 'webhook_in', 'inbound webhook');
    }

    public static function webhookOutbound(): self
    {
        return new self('webhook_outbound', 'webhook_out', 'outbound webhook');
    }

    public static function canvas(): self
    {
        return new self('canvas', 'canvas', 'canvas');
    }
}
