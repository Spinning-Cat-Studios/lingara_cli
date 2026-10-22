<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Json;
use Lingara\Cli\Project\ManifestRules;

/**
 * D7 step 2: the web planner's rules (`plan.ts`) over field nodes.
 *
 * Ops come out in topological order and every client reference is a
 * backward `{handle}`. "Still live" means live **and** still on the recorded
 * client: a recorded client is live when the client list has a row with its
 * `runtime_id`; a webhook or app when its list has its `runtime_id` under
 * that client's platform object; a secret when its client is (the platform
 * lists carry no secrets, and a recorded secret is never re-planned). A node
 * with `custom: true` runs a script, which only the browser may do, so it
 * refuses the plan by its handle.
 *
 * Records are `handle → runtime id`; the live lists are keyed by node kind
 * (`client`, `webhook`, `app`), each row `{id, runtime_id, parent_id}`.
 */
final class Plan
{
    /** @return array{id: string, runtime_id: string, parent_id: string} one list row, as the planner joins it */
    public static function row(mixed $v): array
    {
        return ['id' => Json::str($v, 'id'), 'runtime_id' => Json::str($v, 'runtime_id'), 'parent_id' => Json::str($v, 'parent_id')];
    }

    /**
     * The platform object id behind a recorded runtime id of `$kind`.
     *
     * @param array<string, list<array{id: string, runtime_id: string, parent_id: string}>> $live
     */
    public static function platformId(array $live, string $kind, string $runtimeId): ?string
    {
        foreach ($live[$kind] ?? [] as $row) {
            if ($row['runtime_id'] === $runtimeId) {
                return $row['id'];
            }
        }
        return null;
    }

    /**
     * The recorded entries still live by the rule above.
     *
     * @param array<string, string> $recorded
     * @param array<string, list<array{id: string, runtime_id: string, parent_id: string}>> $live
     * @return array<string, string>
     */
    public static function stillLive(Definition $def, array $recorded, array $live): array
    {
        $kept = [];
        foreach ($def->topoOrder() ?? [] as $node) {
            $record = $recorded[$node['id']] ?? null;
            if ($record === null) {
                continue;
            }
            $isClient = $node['kind'] === 'client';
            $clientRuntime = $isClient ? $record : ($kept[$def->clientOf($node['id']) ?? ''] ?? null);
            $client = $clientRuntime === null ? null : self::platformId($live, 'client', $clientRuntime);
            if ($client !== null && ($isClient || self::dependantLive($node['kind'], $record, $client, $live))) {
                $kept[$node['id']] = $record;
            }
        }
        return $kept;
    }

    /** @param array<string, list<array{id: string, runtime_id: string, parent_id: string}>> $live */
    private static function dependantLive(string $kind, string $record, string $clientId, array $live): bool
    {
        if ($kind === 'secret') {
            return true;
        }
        foreach ($live[$kind] ?? [] as $row) {
            if ($row['runtime_id'] === $record && $row['parent_id'] === $clientId) {
                return true;
            }
        }
        return false;
    }

    /**
     * D7 step 2: every field node's op, in plan order, over the kept records.
     *
     * @param array<string, string> $kept
     * @return list<array<string, mixed>> `{handle, kind, op, client?: {handle}, id?, body?}`, the dry-run's shape
     */
    public static function build(Definition $def, array $kept): array
    {
        $order = $def->topoOrder() ?? throw new Refusal('cycle', '', 'the canvas has a cycle');
        foreach ($order as $node) {
            if ($node['custom']) {
                throw new Refusal('script', $node['id'], 'runs a script');
            }
        }
        $ops = [];
        foreach ($order as $node) {
            // A recorded secret is never re-planned.
            if (!($node['kind'] === 'secret' && isset($kept[$node['id']]))) {
                $ops[] = self::opFor($def, $node, $kept[$node['id']] ?? null);
            }
        }
        return $ops;
    }

    /**
     * One node's op: `create` without a kept record, `update` with one; a dependant names its one client.
     *
     * @param array{id: string, kind: string, fields: \stdClass, custom: bool} $node
     * @return array<string, mixed>
     */
    private static function opFor(Definition $def, array $node, ?string $record): array
    {
        $client = $node['kind'] === 'client' ? null : $def->clientOf($node['id']);
        if ($node['kind'] !== 'client' && $client === null) {
            throw new Refusal('no_client', $node['id'], 'needs exactly one client node upstream');
        }
        $op = ['handle' => $node['id'], 'kind' => $node['kind'], 'op' => $record === null ? 'create' : 'update'];
        if ($client !== null && $record === null) {
            $op['client'] = ['handle' => $client];
        }
        if ($record !== null) {
            $op['id'] = $record;
        }
        $body = self::body($node, $record === null);
        if ($body !== null) {
            $op['body'] = $body;
        }
        return $op;
    }

    /**
     * @param array{id: string, kind: string, fields: \stdClass, custom: bool} $node
     * @return array<string, mixed>|null
     */
    private static function body(array $node, bool $create): ?array
    {
        try {
            $body = match ($node['kind']) {
                'client' => Spec::clientBody($node['fields'], $create),
                'webhook' => Spec::webhookBody($node['fields']),
                'app' => Spec::appBody($node['fields']),
                default => null,
            };
        } catch (\InvalidArgumentException $e) {
            throw new Refusal('fields', $node['id'], $e->getMessage());
        }
        $manifest = $body['manifest'] ?? null;
        $renderUrl = is_array($manifest) && is_string($manifest['render_url'] ?? null) ? $manifest['render_url'] : '';
        if ($node['kind'] === 'app' && ManifestRules::ephemeral($renderUrl)) {
            throw new Refusal('ephemeral', $node['id'], 'renders from an ephemeral URL');
        }
        return $body;
    }
}
