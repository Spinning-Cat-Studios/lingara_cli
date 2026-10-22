<?php

declare(strict_types=1);

namespace Lingara\Cli\Literal;

/**
 * The literal subset's tokens (spec/connection-literal.md; ADR 16.10.26ae
 * D2): identifiers, JSON-escaped strings in either quote, finite JSON
 * numbers and punctuation, each with its 1-based line and column, counted
 * in code points. A comment, a template literal or a character the subset
 * does not know stops the scan. A token is `['ident'|'str'|'num'|'punct' =>
 * value, 'at' => [line, column]]`.
 */
final class Lexer
{
    private const PUNCT = '{}[](),:=.';
    private const SIMPLE = ['"' => '"', '\\' => '\\', '/' => '/', 'b' => "\x08", 'f' => "\x0c", 'n' => "\n", 'r' => "\r", 't' => "\t"];

    private int $i = 0;

    private int $col = 1;

    /** @param list<string> $chars */
    private function __construct(private readonly array $chars, private int $line)
    {
    }

    /** @return list<array<string, mixed>> every token from `$text`, which starts at line `$firstLine` */
    public static function tokens(string $text, int $firstLine): array
    {
        $lx = new self(mb_str_split($text), $firstLine);
        $out = [];
        while (($c = $lx->peek()) !== null) {
            if (in_array($c, [' ', "\t", "\r", "\n"], true)) {
                $lx->bump();
                continue;
            }
            $start = [$lx->line, $lx->col];
            $out[] = [...$lx->one($c, $start), 'at' => $start];
        }
        return $out;
    }

    private function peek(int $ahead = 0): ?string
    {
        return $this->chars[$this->i + $ahead] ?? null;
    }

    private function bump(): ?string
    {
        $c = $this->chars[$this->i] ?? null;
        if ($c === null) {
            return null;
        }
        $this->i++;
        [$this->line, $this->col] = $c === "\n" ? [$this->line + 1, 1] : [$this->line, $this->col + 1];
        return $c;
    }

    private function takeWhile(string $pattern): string
    {
        $out = '';
        while (($c = $this->peek()) !== null && preg_match($pattern, $c) === 1) {
            $out .= $this->bump();
        }
        return $out;
    }

    /**
     * @param array{int, int} $start
     * @return array<string, mixed>
     */
    private function one(string $c, array $start): array
    {
        if ($c === '/' && in_array($this->peek(1), ['/', '*'], true)) {
            throw Refusal::at('comment', $start);
        }
        if ($c === '`') {
            throw Refusal::at('text', $start);
        }
        if ($c === '"' || $c === "'") {
            return ['str' => $this->string($start)];
        }
        if ($c === '-' || ctype_digit($c)) {
            return ['num' => $this->number($start)];
        }
        if (preg_match('/[A-Za-z_$]/', $c) === 1) {
            return ['ident' => $this->takeWhile('/[A-Za-z0-9_$]/')];
        }
        if (!str_contains(self::PUNCT, $c)) {
            throw Refusal::at('syntax', $start);
        }
        $this->bump();
        return ['punct' => $c];
    }

    /** @param array{int, int} $start */
    private function string(array $start): string
    {
        $quote = (string) $this->bump();
        $out = '';
        while (($c = $this->stringChar($quote, $start)) !== null) {
            $out .= $c;
        }
        return $out;
    }

    /**
     * One character of a string, unescaped; null at its closing quote.
     *
     * @param array{int, int} $start
     */
    private function stringChar(string $quote, array $start): ?string
    {
        $c = $this->bump();
        if ($c === null || in_array($c, ["\n", "\r", "\t"], true) || ($c === '$' && $this->peek() === '{')) {
            throw Refusal::at('text', $start);
        }
        if ($c === $quote) {
            return null;
        }
        return $c === '\\' ? ($this->escape($quote) ?? throw Refusal::at('text', $start)) : $c;
    }

    /** JSON's escapes, and `\'` inside single quotes. */
    private function escape(string $quote): ?string
    {
        $c = $this->bump();
        if ($c !== null && isset(self::SIMPLE[$c])) {
            return self::SIMPLE[$c];
        }
        if ($c === "'" && $quote === "'") {
            return "'";
        }
        if ($c !== 'u') {
            return null;
        }
        $hex = implode('', [$this->bump(), $this->bump(), $this->bump(), $this->bump()]);
        // A lone surrogate half has no UTF-8 spelling; it reads as U+FFFD rather than refusing a JSON-valid escape.
        return preg_match('/^[0-9A-Fa-f]{4}$/', $hex) === 1 ? (mb_chr((int) hexdec($hex), 'UTF-8') ?: "\u{FFFD}") : null;
    }

    /**
     * A finite JSON number, `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`, as JSON
     * would decode it, so `1.0` stays a float.
     *
     * @param array{int, int} $start
     */
    private function number(array $start): int|float
    {
        $text = $this->takeWhile('/[A-Za-z0-9.+\-]/');
        $value = preg_match('/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/', $text) === 1 ? json_decode($text) : null;
        return (is_int($value) || (is_float($value) && is_finite($value))) ? $value : throw Refusal::at('number', $start);
    }
}
