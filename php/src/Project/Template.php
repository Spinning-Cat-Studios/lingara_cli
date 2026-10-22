<?php

declare(strict_types=1);

namespace Lingara\Cli\Project;

use Devium\Toml\Toml;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Fail;

/**
 * Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the fixed
 * `{{key}}` set, the array syntax each language's bare template uses, the
 * two renaming rules, and `template.toml`, read and never written. The keys
 * are `{name, display_name, description, port, slots, context}`.
 */
final class Template
{
    private const META = 'template.toml';

    /**
     * `["a", "b"]` in the syntax the template's language writes an array in.
     *
     * @param list<string> $items
     */
    public static function array(string $lang, string $variant, array $items): string
    {
        $inner = implode(', ', array_map(static fn (string $i): string => "\"{$i}\"", $items));
        return match ($variant === 'bare' ? $lang : '') {
            'go' => "[]string{{$inner}}",
            'java' => "List.of({$inner})",
            'kotlin' => "listOf({$inner})",
            default => "[{$inner}]",
        };
    }

    /** A segment starting `_` is written with `.`; a `.tmpl` suffix is dropped. */
    public static function projectPath(string $relative): string
    {
        $segments = array_map(static fn (string $s): string => str_starts_with($s, '_') ? '.' . substr($s, 1) : $s, explode('/', $relative));
        $last = count($segments) - 1;
        if (str_ends_with($segments[$last], '.tmpl')) {
            $segments[$last] = substr($segments[$last], 0, -strlen('.tmpl'));
        }
        return implode('/', $segments);
    }

    /** @param array{name: string, display_name: string, description: string, port: int, slots: list<string>, context: list<string>} $keys */
    public static function substitute(string $text, string $lang, string $variant, array $keys): string
    {
        return strtr($text, [
            '{{name}}' => $keys['name'],
            '{{display_name}}' => $keys['display_name'],
            '{{description}}' => $keys['description'],
            '{{port}}' => (string) $keys['port'],
            '{{slots}}' => self::array($lang, $variant, $keys['slots']),
            '{{context}}' => self::array($lang, $variant, $keys['context']),
        ]);
    }

    /** @return array{start: ?string, manifest: ?array{string, string}} the row's start command and its manifest source */
    private static function meta(string $text): array
    {
        try {
            $meta = Toml::decode($text, true);
        } catch (\Throwable) {
            $meta = [];
        }
        $get = static fn (string $k): ?string => is_array($meta) && is_string($meta[$k] ?? null) ? $meta[$k] : null;
        $source = $get('manifest') !== null ? ['manifest', (string) $get('manifest')] : null;
        $source ??= $get('manifest_command') !== null ? ['manifest_command', (string) $get('manifest_command')] : null;
        return ['start' => $get('start'), 'manifest' => $source];
    }

    /**
     * Every file of `<lang>/<variant>`, rendered, and its `template.toml`.
     *
     * @param array{name: string, display_name: string, description: string, port: int, slots: list<string>, context: list<string>} $keys
     * @return array{list<array{string, string}>, array{start: ?string, manifest: ?array{string, string}}}
     */
    public static function render(string $lang, string $variant, array $keys): array
    {
        $files = Assets::template($lang, $variant);
        if ($files === []) {
            throw Fail::failure("this lingara carries no {$lang} {$variant} template");
        }
        $meta = ['start' => null, 'manifest' => null];
        $out = [];
        foreach ($files as [$path, $bytes]) {
            if ($path === self::META) {
                $meta = self::meta($bytes);
            } else {
                $out[] = [self::projectPath($path), self::substitute($bytes, $lang, $variant, $keys)];
            }
        }
        return [$out, $meta];
    }

    /** @param list<array{string, string}> $files writes every file under `$dir`, creating directories as it goes */
    public static function writeAll(string $dir, array $files): void
    {
        foreach ($files as [$path, $bytes]) {
            $target = "{$dir}/{$path}";
            if (!is_dir(dirname($target))) {
                mkdir(dirname($target), 0777, true);
            }
            file_put_contents($target, $bytes);
        }
    }
}
