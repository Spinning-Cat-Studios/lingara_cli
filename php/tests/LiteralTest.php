<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests;

use Lingara\Cli\Core\Json;
use Lingara\Cli\Literal\ConnectionLiteral;
use Lingara\Cli\Literal\Refusal;
use PHPUnit\Framework\TestCase;

/**
 * spec/connection-literal.md's fixtures, which hold all seven kits to one
 * answer (ADR 16.10.26ae D2): every valid file converts to its wire
 * manifest byte for byte, member order included, and every refused one is
 * refused with its code at its position.
 */
final class LiteralTest extends TestCase
{
    private static function fixtures(): string
    {
        return dirname(__DIR__, 2) . '/spec/connection-fixtures';
    }

    public function testValidFilesConvert(): void
    {
        foreach (glob(self::fixtures() . '/valid/*.js') ?: [] as $file) {
            $want = substr($file, 0, -3) . '.json';
            if (!is_file($want)) {
                $this->assertNotEmpty(ConnectionLiteral::statements((string) file_get_contents($file)) ?: [true]);
                continue;
            }
            $manifest = ConnectionLiteral::appManifest((string) file_get_contents($file));
            $this->assertSame(Json::encode(Json::decode((string) file_get_contents($want))), Json::encode($manifest), basename($file));
        }
    }

    public function testRefusedFilesAreRefusedWhereTheFixtureSays(): void
    {
        foreach (glob(self::fixtures() . '/refused/*.js') ?: [] as $file) {
            $want = Json::decode((string) file_get_contents(substr($file, 0, -3) . '.json'));
            try {
                ConnectionLiteral::appManifest((string) file_get_contents($file));
                $this->fail(basename($file) . ' was not refused');
            } catch (Refusal $r) {
                $got = $r->statement !== null
                    ? ['code' => 'connection_scripted', 'statement' => $r->statement]
                    : ['code' => $r->why, 'line' => $r->pos[0] ?? 0, 'column' => $r->pos[1] ?? 0];
                $this->assertEquals($want, (object) $got, basename($file));
            }
        }
    }
}
