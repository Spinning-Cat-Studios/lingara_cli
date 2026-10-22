<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

/**
 * Writing `platform.toml`, its example and `.gitignore`, byte for byte as
 * spec/templates.md says (ADR 16.10.26ae D1, D4), so every kit leaves the
 * same files behind. The edits are line-based on purpose: a developer's
 * comments and layout outside the touched row survive.
 *
 * A new row is `{name, path, port, start?, manifest?: [key, value]}`.
 */
final class Write
{
    public const FILE = 'platform.toml';
    public const EXAMPLE = 'platform.toml.example';
    public const PLACEHOLDER_ID = '00000000-0000-0000-0000-000000000000';
    public const FILE_HEADER = "# platform.toml — gitignored; copy from platform.toml.example.\n# Never put a secret here. lingara refuses to read a value that looks like one.\nschema = 1\n";
    public const EXAMPLE_HEADER = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n# then run lingara apps update to register your own private copy of each app.\nschema = 1\n";

    /** A TOML basic string. */
    public static function quote(string $text): string
    {
        return '"' . str_replace(['\\', '"'], ['\\\\', '\\"'], $text) . '"';
    }

    /** @param array{name: string, path: string, port: int, start?: ?string, manifest?: ?array{string, string}} $row */
    private static function rowText(array $row, ?string $id): string
    {
        $out = "\n[[apps]]\nname = " . self::quote($row['name']) . "\n";
        if ($id !== null) {
            $out .= 'id = ' . self::quote($id) . "\n";
        }
        $out .= 'path = ' . self::quote($row['path']) . "\nport = {$row['port']}\n";
        if (($row['start'] ?? null) !== null) {
            $out .= 'start = ' . self::quote($row['start']) . "\n";
        }
        if (($row['manifest'] ?? null) !== null) {
            $out .= "{$row['manifest'][0]} = " . self::quote($row['manifest'][1]) . "\n";
        }
        return $out;
    }

    private static function readOr(string $path, string $header): string
    {
        $text = is_file($path) ? @file_get_contents($path) : false;
        if ($text === false) {
            return $header;
        }
        return $text !== '' && !str_ends_with($text, "\n") ? "{$text}\n" : $text;
    }

    /** @return list<string> */
    private static function lines(string $text): array
    {
        return $text === '' ? [] : explode("\n", str_ends_with($text, "\n") ? substr($text, 0, -1) : $text);
    }

    /** @param list<string> $lines */
    private static function joined(array $lines): string
    {
        return implode('', array_map(static fn (string $l): string => "{$l}\n", $lines));
    }

    /** @return array{int, int}|null the row named `$name`'s line span `[start, end)`: up to the next table header that is not its own `[apps.env]` */
    private static function spanOf(string $text, string $name): ?array
    {
        $lines = self::lines($text);
        $want = 'name = ' . self::quote($name);
        foreach ($lines as $start => $line) {
            if (trim($line) !== '[[apps]]') {
                continue;
            }
            $end = count($lines);
            for ($i = $start + 1; $i < count($lines); $i++) {
                if (str_starts_with(ltrim($lines[$i]), '[') && trim($lines[$i]) !== '[apps.env]') {
                    $end = $i;
                    break;
                }
            }
            if (in_array($want, array_map('trim', array_slice($lines, $start, $end - $start)), true)) {
                return [$start, $end];
            }
        }
        return null;
    }

    public static function hasRow(string $text, string $name): bool
    {
        return self::spanOf($text, $name) !== null;
    }

    /**
     * Appends the row to `$file` (with `$id`, if any) and, when the example
     * beside it has none of that name, to the example.
     *
     * @param array{name: string, path: string, port: int, start?: ?string, manifest?: ?array{string, string}} $row
     */
    public static function pinRow(string $file, array $row, ?string $id): void
    {
        file_put_contents($file, self::readOr($file, self::FILE_HEADER) . self::rowText($row, $id));
        $example = dirname($file) . '/' . self::EXAMPLE;
        $current = self::readOr($example, self::EXAMPLE_HEADER);
        if (!self::hasRow($current, $row['name'])) {
            file_put_contents($example, $current . self::rowText($row, self::PLACEHOLDER_ID));
        }
    }

    /** Writes `$id` into the row named `$name`: replaces its `id` line, or adds one after its `name` line. */
    public static function setId(string $file, string $name, string $id): void
    {
        $text = (string) @file_get_contents($file);
        $span = self::spanOf($text, $name);
        if ($span === null) {
            return;
        }
        $lines = self::lines($text);
        $at = static function (string $key) use ($lines, $span): ?int {
            for ($i = $span[0]; $i < $span[1]; $i++) {
                if (preg_match('/^\s*' . $key . '\s*=/', $lines[$i]) === 1) {
                    return $i;
                }
            }
            return null;
        };
        $idLine = 'id = ' . self::quote($id);
        $existing = $at('id');
        if ($existing !== null) {
            $lines[$existing] = $idLine;
        } else {
            array_splice($lines, ($at('name') ?? $span[0]) + 1, 0, [$idLine]);
        }
        file_put_contents($file, self::joined(array_values($lines)));
    }

    /** Removes the row named `$name`; false when there is none. */
    public static function removeRow(string $file, string $name): bool
    {
        $text = (string) @file_get_contents($file);
        $span = self::spanOf($text, $name);
        if ($span === null) {
            return false;
        }
        $lines = self::lines($text);
        $start = $span[0];
        while ($span[1] === count($lines) && $start > 0 && trim($lines[$start - 1]) === '') {
            $start--;
        }
        file_put_contents($file, self::joined([...array_slice($lines, 0, $start), ...array_slice($lines, $span[1])]));
        return true;
    }

    /** Adds a `platform.toml` line to `<dir>/.gitignore` when no line is one. */
    public static function ensureGitignore(string $dir): void
    {
        $path = $dir . '/.gitignore';
        $text = self::readOr($path, '');
        foreach (explode("\n", $text) as $line) {
            if (trim($line) === self::FILE) {
                return;
            }
        }
        file_put_contents($path, $text . self::FILE . "\n");
    }
}
