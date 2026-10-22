<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests;

use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Project\PlatformToml;
use Lingara\Cli\Project\Write;
use PHPUnit\Framework\TestCase;

/**
 * `platform.toml`'s refusals and writing rules (ADR 16.10.26ae D4, AC6),
 * against files in a temp directory, as the Rust kit's
 * `platform_toml_tests.rs` checks them.
 */
final class PlatformTomlTest extends TestCase
{
    private static function file(string $text): string
    {
        $dir = sys_get_temp_dir() . '/lingara-php-toml-' . bin2hex(random_bytes(6));
        mkdir($dir);
        file_put_contents("{$dir}/platform.toml", $text);
        return "{$dir}/platform.toml";
    }

    private static function refusal(string $text): Fail
    {
        try {
            PlatformToml::load(self::file($text), 'platform.toml');
        } catch (Fail $fail) {
            return $fail;
        }
        self::fail('expected a refusal');
    }

    public function testSecretValuesAndKeysAreRefusedByName(): void
    {
        $row = "schema = 1\n[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\n";
        $refused = ['HOOK = "lgr_whsec_abcdefgh12345"' => 'apps.env.HOOK', 'client_secret = "x"' => 'apps.env.client_secret', 'API_TOKEN = "x"' => 'apps.env.API_TOKEN', 'db_password = "x"' => 'apps.env.db_password'];
        foreach ($refused as $line => $key) {
            $fail = self::refusal("{$row}{$line}\n");
            $this->assertSame(ExitCode::PlatformTomlSecret, $fail->exit, $key);
            $this->assertStringContainsString($key, $fail->getMessage());
            $this->assertStringNotContainsString('lgr_whsec_abcdefgh12345', $fail->getMessage());
        }
        $accepted = PlatformToml::load(self::file("{$row}LINGARA_CLIENT_ID = \"lgr_cid_abcdefgh12345\"\nTOKENIZER = \"bpe\"\n"), 'platform.toml');
        $this->assertSame('a', $accepted['apps'][0]['name']);
        $this->assertSame(PlatformToml::DEFAULT_PORT, $accepted['apps'][0]['port']);
        foreach (["schema = 2\n", "schema = 1\n[[apps]]\nname = \"a\"\npath = \".\"\nlisted = true\n", "schema = 1\n[[apps]]\nname = \"A\"\npath = \".\"\n", "schema = 1\nport = \n"] as $text) {
            $this->assertSame(ExitCode::PlatformTomlSchema, self::refusal($text)->exit, $text);
        }
    }

    public function testRowsAreWrittenAsTheSpecSpellsThem(): void
    {
        $path = self::file('');
        unlink($path);
        Write::pinRow($path, ['name' => 'demo', 'path' => '.', 'port' => 8787, 'start' => 'npm start', 'manifest' => ['manifest', 'lingara/connection.js']], null);
        Write::setId($path, 'demo', '7d1e2f30-0000-4000-8000-000000000001');
        $want = Write::FILE_HEADER . "\n[[apps]]\nname = \"demo\"\nid = \"7d1e2f30-0000-4000-8000-000000000001\"\npath = \".\"\nport = 8787\nstart = \"npm start\"\nmanifest = \"lingara/connection.js\"\n";
        $this->assertSame($want, file_get_contents($path));
        $example = (string) file_get_contents(dirname($path) . '/' . Write::EXAMPLE);
        $this->assertStringStartsWith(Write::EXAMPLE_HEADER, $example);
        $this->assertStringContainsString('id = "' . Write::PLACEHOLDER_ID . '"', $example);
        $this->assertTrue(Write::removeRow($path, 'demo'));
        $this->assertSame(Write::FILE_HEADER, file_get_contents($path));
        $this->assertFalse(Write::removeRow($path, 'demo'));
    }
}
