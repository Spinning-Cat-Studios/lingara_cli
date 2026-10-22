<?php

declare(strict_types=1);

namespace Lingara\Cli\Canvas;

use Lingara\Cli\Core\Json;

/**
 * A canvas definition (Backend 2.10.26e §2), as far as apply reads it, and
 * the order the web's `flowDefinition.ts` walks it in.
 *
 * @phpstan-type Node array{id: string, kind: string, fields: \stdClass, custom: bool}
 */
final class Definition
{
    /**
     * @param list<array{id: string, kind: string, fields: \stdClass, custom: bool}> $nodes
     * @param list<array{string, string}> $edges
     */
    public function __construct(public readonly array $nodes, public readonly array $edges)
    {
    }

    public static function read(mixed $value): self
    {
        $nodes = array_map(static fn (mixed $n): array => [
            'id' => Json::str($n, 'id'),
            'kind' => Json::str($n, 'kind'),
            'fields' => Json::object(Json::get($n, 'fields')),
            // A script node: it runs only in the browser's sandbox (2.10.26b D5).
            'custom' => Json::get($n, 'custom') === true || is_string(Json::get($n, 'script')),
        ], Json::items($value, 'nodes'));
        $edges = array_map(static fn (mixed $e): array => [Json::str($e, 'from'), Json::str($e, 'to')], Json::items($value, 'edges'));
        return new self($nodes, $edges);
    }

    /**
     * Kahn's algorithm, ties broken by node order, as `topoOrder`; null on a cycle.
     *
     * @return list<array{id: string, kind: string, fields: \stdClass, custom: bool}>|null
     */
    public function topoOrder(): ?array
    {
        $indegree = [];
        foreach ($this->nodes as $n) {
            $indegree[$n['id']] = count(array_filter($this->edges, static fn (array $e): bool => $e[1] === $n['id']));
        }
        [$done, $order] = [[], []];
        while (count($order) < count($this->nodes)) {
            $next = null;
            foreach ($this->nodes as $n) {
                if (!isset($done[$n['id']]) && $indegree[$n['id']] === 0) {
                    $next = $n;
                    break;
                }
            }
            if ($next === null) {
                return null;
            }
            [$order[], $done[$next['id']]] = [$next, true];
            foreach ($this->edges as [$from, $to]) {
                if ($from === $next['id']) {
                    $indegree[$to] = ($indegree[$to] ?? 0) - 1;
                }
            }
        }
        return $order;
    }

    /** A dependent node's client: its one direct upstream `client` node, or null for zero or two. */
    public function clientOf(string $handle): ?string
    {
        $parents = [];
        foreach ($this->nodes as $n) {
            if ($n['kind'] === 'client' && in_array([$n['id'], $handle], $this->edges, true)) {
                $parents[] = $n['id'];
            }
        }
        return count($parents) === 1 ? $parents[0] : null;
    }
}
