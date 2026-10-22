<?php

declare(strict_types=1);

namespace Lingara\Cli\Literal;

use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * The connection literal subset (ADR 16.10.26ae D2; spec/connection-
 * literal.md): `lingara/connection.js` read without evaluating JavaScript.
 * `Lexer` makes the tokens, `Parser` the statements, `Convert` turns the one
 * `lingara.app` statement's `manifest` into the wire manifest.
 */
final class ConnectionLiteral
{
    /** The file's first line, byte for byte. */
    public const HEADER = '// lingara connection v1 (schema 2)';

    private const WHAT = [
        'header' => 'the first line must be `// lingara connection v1 (schema 2)`',
        'comment' => 'comments go inside a script; the builder rewrites everything outside them',
        'text' => 'write text in quotes, with only the escapes JSON knows, and no raw tab or line break',
        'number' => 'write finite numbers in decimal, as JSON does',
        'key' => 'write each key once',
        'handle' => 'this handle is already declared',
        'reference' => 'this names no statement declared above it',
        'syntax' => 'not part of the literal subset',
        'app_count' => 'the file needs exactly one lingara.app statement',
        'option' => 'an app takes only client and manifest',
        'manifest_key' => 'not a manifest key lingara reads, or a required one is missing',
    ];

    /** As B2 prints it: `connection_scripted`, else `usage` with `<file>:<line>:<column>: <what>`. */
    public static function fail(Refusal $r, string $file): Fail
    {
        if ($r->statement !== null) {
            return Fail::message(ExitCode::ConnectionScripted, 'connection_scripted', ['file' => $file, 'statement' => $r->statement]);
        }
        [$line, $column] = $r->pos ?? [1, 1];
        $what = self::WHAT[$r->why] ?? self::WHAT['syntax'];
        return new Fail(ExitCode::Usage, 'connection_literal', "{$file}:{$line}:{$column}: {$what} ({$r->why})");
    }

    /** @return list<array<string, mixed>> every statement in `$text` */
    public static function statements(string $text): array
    {
        $newline = strpos($text, "\n");
        $header = rtrim($newline === false ? $text : substr($text, 0, $newline), "\r");
        if ($header !== self::HEADER) {
            throw Refusal::at('header', [1, 1]);
        }
        $body = $newline === false ? '' : substr($text, $newline + 1);
        $lines = max(1, count(explode("\n", $text)) - (str_ends_with($text, "\n") ? 1 : 0));
        return Parser::statements(Lexer::tokens($body, 2), [$lines + 1, 1]);
    }

    /** The wire manifest of the file's one `lingara.app` statement. */
    public static function appManifest(string $text): \stdClass
    {
        $apps = array_values(array_filter(self::statements($text), static fn (array $s): bool => $s['kind'] === 'app'));
        if ($apps === []) {
            throw Refusal::at('app_count', [1, 1]);
        }
        if (count($apps) > 1) {
            throw Refusal::at('app_count', $apps[1]['at']);
        }
        $value = $apps[0]['value'];
        if (!isset($value['obj'])) {
            throw Refusal::at('syntax', $apps[0]['at']);
        }
        $manifest = null;
        foreach ($value['obj'] as $m) {
            if ($m['key'] !== 'client' && $m['key'] !== 'manifest') {
                throw Refusal::at('option', $m['at']);
            }
            $manifest = $m['key'] === 'manifest' ? $m['value'] : $manifest;
        }
        if ($manifest === null || !isset($manifest['obj'])) {
            throw Refusal::at('manifest_key', $value['at']);
        }
        return Convert::manifest($manifest['obj'], $manifest['at']);
    }
}
