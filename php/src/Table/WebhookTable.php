<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

use Lingara\Cli\Handlers\WebhookInboundHandlers;
use Lingara\Cli\Handlers\WebhookOutboundHandlers;

/** `webhook inbound|outbound …` (ADR 16.10.26ad D1), plus `replace` and `test` on outbound. */
final class WebhookTable
{
    private const EVENT = ['name' => 'event', 'type' => 'string', 'repeatable' => true, 'value' => 'type'];
    private const NAME = ['name' => 'name', 'type' => 'string', 'help' => 'The new name'];

    public const COMMANDS = [
        'webhook inbound create' => [
            'help' => "Create an inbound webhook and its client, and show the client's secret once.",
            'run' => [WebhookInboundHandlers::class, 'create'],
            'flags' => [
                ['name' => 'name', 'type' => 'string', 'required' => true, 'help' => "The webhook's name"],
                [...self::EVENT, 'help' => 'An inbound event type it accepts; absent, every one'],
            ],
        ],
        'webhook inbound list' => ['help' => 'List your inbound webhooks.', 'run' => [WebhookInboundHandlers::class, 'list']],
        'webhook inbound get' => ['help' => 'Show one inbound webhook.', 'run' => [WebhookInboundHandlers::class, 'get'], 'args' => Flags::ID],
        'webhook inbound update' => [
            'help' => 'Rename an inbound webhook, or replace its event types.',
            'run' => [WebhookInboundHandlers::class, 'update'],
            'args' => Flags::ID,
            'flags' => [self::NAME, [...self::EVENT, 'help' => 'Replaces the event types']],
        ],
        'webhook inbound delete' => [
            'help' => 'Delete an inbound webhook, or one version. Its client stays.',
            'run' => [WebhookInboundHandlers::class, 'delete'],
            'args' => Flags::ID,
            'flags' => Flags::DELETE,
        ],
        'webhook outbound create' => [
            'help' => 'Create an outbound webhook, and show its signing secret once.',
            'run' => [WebhookOutboundHandlers::class, 'create'],
            'flags' => [
                ['name' => 'client', 'type' => 'string', 'required' => true, 'value' => 'id', 'help' => 'The OAuth client it belongs to'],
                ['name' => 'url', 'type' => 'string', 'required' => true, 'value' => 'url', 'help' => 'The endpoint deliveries go to'],
                [...self::EVENT, 'required' => true, 'help' => 'An event type to deliver'],
            ],
        ],
        'webhook outbound list' => ['help' => 'List your outbound webhooks.', 'run' => [WebhookOutboundHandlers::class, 'list']],
        'webhook outbound get' => ['help' => 'Show one outbound webhook.', 'run' => [WebhookOutboundHandlers::class, 'get'], 'args' => Flags::ID],
        'webhook outbound update' => [
            'help' => "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.",
            'run' => [WebhookOutboundHandlers::class, 'update'],
            'args' => Flags::ID,
            'flags' => [
                ['name' => 'url', 'type' => 'string', 'value' => 'url', 'help' => 'The new endpoint'],
                [...self::EVENT, 'help' => 'Replaces the event types'],
                ['name' => 'enable', 'type' => 'bool', 'help' => 'Resume deliveries'],
                ['name' => 'disable', 'type' => 'bool', 'help' => 'Pause deliveries'],
            ],
            'conflicts' => [['enable', 'disable']],
        ],
        'webhook outbound replace' => [
            'help' => 'Start again: a new endpoint with a new signing secret, then the old one deleted.',
            'run' => [WebhookOutboundHandlers::class, 'replace'],
            'args' => Flags::ID,
            'flags' => [
                ['name' => 'url', 'type' => 'string', 'value' => 'url', 'help' => "The new endpoint; absent, the old one's"],
                [...self::EVENT, 'help' => "The new event types; absent, the old one's"],
                ...Flags::CONFIRM,
            ],
        ],
        'webhook outbound test' => ['help' => 'Send a test delivery.', 'run' => [WebhookOutboundHandlers::class, 'test'], 'args' => Flags::ID],
        'webhook outbound delete' => [
            'help' => 'Delete an outbound webhook, or one version.',
            'run' => [WebhookOutboundHandlers::class, 'delete'],
            'args' => Flags::ID,
            'flags' => Flags::DELETE,
        ],
    ];
}
