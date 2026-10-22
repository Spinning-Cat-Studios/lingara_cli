<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

use Devium\Toml\Toml;

/**
 * Every shared file this kit reads, from its own `assets/` (ADR 16.10.26af
 * D8): `endpoints.json` (where production is), `messages.en.json` (what a
 * prompt says, byte for byte), `tools.toml` (cloudflared's floor), the
 * platform.toml JSON Schema and the app templates. All are committed copies
 * held equal to their sources by `make check-kit-assets`, because the split
 * mirror and the PHAR carry only `php/`. Read through `__DIR__`, which is a
 * `phar://` path inside the PHAR, so both ship the same bytes.
 */
final class Assets
{
    /** @var array<string, string>|null */
    private static ?array $texts = null;

    public static function dir(): string
    {
        return dirname(__DIR__, 2) . '/assets';
    }

    public static function read(string $name): string
    {
        $text = @file_get_contents(self::dir() . '/' . $name);
        return $text === false ? '' : $text;
    }

    /**
     * The message `$id` with each `{name}` filled; an id the file lacks
     * prints as itself, which a test pins never to happen.
     *
     * @param array<string, string> $vars
     */
    public static function message(string $id, array $vars = []): string
    {
        $text = self::texts()[$id] ?? $id;
        foreach ($vars as $key => $value) {
            $text = str_replace('{' . $key . '}', $value, $text);
        }
        return $text;
    }

    public static function hasMessage(string $id): bool
    {
        return isset(self::texts()[$id]);
    }

    /** @return array<string, string> */
    private static function texts(): array
    {
        if (self::$texts === null) {
            self::$texts = [];
            foreach (Json::items(Json::decode(self::read('messages.en.json')), 'messages') as $m) {
                self::$texts[Json::str($m, 'id')] = Json::str($m, 'text');
            }
        }
        return self::$texts;
    }

    /** @return array{client_id: string, api_url: string, platform_url: string} */
    public static function endpoints(): array
    {
        $file = Json::decode(self::read('endpoints.json'));
        return [
            'client_id' => Json::opt($file, 'client_id') ?? 'lingara-cli',
            'api_url' => Json::opt($file, 'api_url') ?? 'https://api.getlingara.com',
            'platform_url' => Json::opt($file, 'platform_url') ?? 'https://platform.getlingara.com',
        ];
    }

    /** @return list<int> `tools.toml`'s `[cloudflared] min_version`, as dotted numbers */
    public static function cloudflaredMinVersion(): array
    {
        try {
            $tools = Toml::decode(self::read('tools.toml'), true);
        } catch (\Throwable) {
            return [];
        }
        $text = is_array($tools) ? (string) ($tools['cloudflared']['min_version'] ?? '') : '';
        return array_values(array_map('intval', array_filter(explode('.', $text), 'is_numeric')));
    }

    /** @return list<array{string, string}> every file of one template (`<lang>/<variant>/`), path relative to it, sorted */
    public static function template(string $lang, string $variant): array
    {
        $root = self::dir() . "/templates/apps/{$lang}/{$variant}";
        if (!is_dir($root)) {
            return [];
        }
        $out = [];
        $walk = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($root, \FilesystemIterator::SKIP_DOTS));
        foreach ($walk as $file) {
            if ($file instanceof \SplFileInfo && $file->isFile()) {
                $relative = str_replace('\\', '/', substr($file->getPathname(), strlen($root) + 1));
                $out[] = [$relative, (string) file_get_contents($file->getPathname())];
            }
        }
        usort($out, static fn (array $a, array $b): int => strcmp($a[0], $b[0]));
        return $out;
    }
}
