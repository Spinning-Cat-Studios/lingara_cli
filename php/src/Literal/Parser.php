<?php

declare(strict_types=1);

namespace Lingara\Cli\Literal;

/**
 * Statements and values over the tokens (spec/connection-literal.md):
 * `const <handle> = lingara.<kind>(<value>)`, one after another. A second
 * call argument is a script, which refuses the file there and then.
 *
 * A value is `['str'|'num'|'bool'|'ref' => …]`, `['nil' => true]`,
 * `['arr' => list<value>]` or `['obj' => list<member>, 'at' => pos]`, a
 * member `['key', 'at', 'value']`; a statement `['handle', 'kind', 'value',
 * 'at']`, `at` being where its `const` is.
 */
final class Parser
{
    private const KINDS = ['client', 'secret', 'webhook', 'app'];

    private int $i = 0;

    /** @var array<string, true> */
    private array $handles = [];

    /**
     * @param list<array<string, mixed>> $toks
     * @param array{int, int} $end
     */
    private function __construct(private readonly array $toks, private readonly array $end)
    {
    }

    /**
     * Every statement of the body, which ends at `$end`.
     *
     * @param list<array<string, mixed>> $toks
     * @param array{int, int} $end
     * @return list<array<string, mixed>>
     */
    public static function statements(array $toks, array $end): array
    {
        $p = new self($toks, $end);
        $out = [];
        while (isset($p->toks[$p->i])) {
            $out[] = $p->statement();
        }
        return $out;
    }

    private function isPunct(string $c): bool
    {
        return ($this->toks[$this->i]['punct'] ?? null) === $c;
    }

    /** @return array<string, mixed> */
    private function next(): array
    {
        return $this->toks[$this->i++] ?? throw Refusal::at('syntax', $this->end);
    }

    /** @return array{int, int} */
    private function punct(string $c): array
    {
        $t = $this->next();
        return ($t['punct'] ?? null) === $c ? $t['at'] : throw Refusal::at('syntax', $t['at']);
    }

    /** @return array{string, array{int, int}} */
    private function ident(): array
    {
        $t = $this->next();
        return isset($t['ident']) ? [(string) $t['ident'], $t['at']] : throw Refusal::at('syntax', $t['at']);
    }

    /** @return array{int, int} */
    private function word(string $want): array
    {
        [$word, $pos] = $this->ident();
        return $word === $want ? $pos : throw Refusal::at('syntax', $pos);
    }

    /** @return array<string, mixed> */
    private function statement(): array
    {
        $pos = $this->word('const');
        [$handle, $handleAt] = $this->ident();
        if (isset($this->handles[$handle])) {
            throw Refusal::at('handle', $handleAt);
        }
        $this->punct('=');
        $this->word('lingara');
        $this->punct('.');
        [$kind, $kindAt] = $this->ident();
        if (!in_array($kind, self::KINDS, true)) {
            throw Refusal::at('syntax', $kindAt);
        }
        $this->punct('(');
        $value = $this->value();
        if ($this->isPunct(',')) {
            throw Refusal::scripted($handle);
        }
        $this->punct(')');
        // Declared only now: a value may name the statements above it, never its own.
        $this->handles[$handle] = true;
        return ['handle' => $handle, 'kind' => $kind, 'value' => $value, 'at' => $pos];
    }

    /** @return array<string, mixed> */
    private function value(): array
    {
        $t = $this->next();
        return match (true) {
            array_key_exists('str', $t) => ['str' => $t['str']],
            array_key_exists('num', $t) => ['num' => $t['num']],
            isset($t['ident']) => $this->wordValue((string) $t['ident'], $t['at']),
            ($t['punct'] ?? null) === '[' => ['arr' => $this->items(']', fn (): array => $this->value())],
            ($t['punct'] ?? null) === '{' => ['obj' => $this->object(), 'at' => $t['at']],
            default => throw Refusal::at('syntax', $t['at']),
        };
    }

    /**
     * @param array{int, int} $pos
     * @return array<string, mixed>
     */
    private function wordValue(string $word, array $pos): array
    {
        return match (true) {
            $word === 'true' || $word === 'false' => ['bool' => $word === 'true'],
            $word === 'null' => ['nil' => true],
            isset($this->handles[$word]) => ['ref' => $word],
            default => throw Refusal::at('reference', $pos),
        };
    }

    /**
     * `item {, item} [,] close`, the opener already read.
     *
     * @param \Closure(): array<string, mixed> $item
     * @return list<array<string, mixed>>
     */
    private function items(string $close, \Closure $item): array
    {
        $out = [];
        while (true) {
            if ($this->isPunct($close)) {
                $this->i++;
                return $out;
            }
            $out[] = $item();
            $t = $this->next();
            if (($t['punct'] ?? null) === $close) {
                return $out;
            }
            if (($t['punct'] ?? null) !== ',') {
                throw Refusal::at('syntax', $t['at']);
            }
        }
    }

    /** @return list<array<string, mixed>> */
    private function object(): array
    {
        $seen = [];
        return $this->items('}', function () use (&$seen): array {
            $t = $this->next();
            $key = $t['ident'] ?? $t['str'] ?? throw Refusal::at('syntax', $t['at']);
            if (isset($seen[$key])) {
                throw Refusal::at('key', $t['at']);
            }
            $seen[$key] = true;
            $this->punct(':');
            return ['key' => (string) $key, 'at' => $t['at'], 'value' => $this->value()];
        });
    }
}
