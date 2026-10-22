<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests;

use Lingara\Cli\Dev\DevRun;
use Lingara\Cli\Dev\Group;
use Lingara\Cli\Project\ProjectManifest;
use PHPUnit\Framework\TestCase;

/**
 * `apps dev`'s children (ADR 16.10.26ae D5, D7 step 8; ADR 16.10.26af D2):
 * each leads its own process group, a stop takes the whole group down, and
 * the app's environment never carries the CLI's own platform credentials.
 */
final class SuperviseTest extends TestCase
{
    public function testStopTakesTheWholeGroup(): void
    {
        $dir = sys_get_temp_dir() . '/lingara-php-group-' . bin2hex(random_bytes(6));
        mkdir($dir);
        // The shell leads the group; its sleeping child writes its pid, then waits.
        $group = Group::spawn(ProjectManifest::shell('sleep 30 & echo $! > child.pid; wait'), $dir, null);
        $deadline = microtime(true) + 5;
        while (!is_file("{$dir}/child.pid") && microtime(true) < $deadline) {
            usleep(20_000);
        }
        usleep(100_000);
        $child = (int) file_get_contents("{$dir}/child.pid");
        $this->assertGreaterThan(0, $child);
        $this->assertSame((int) $group->process->getPid(), posix_getpgid($child), 'the child shares its leader\'s new group');
        $this->assertNotSame(posix_getpgid((int) getmypid()), posix_getpgid($child), 'a terminal\'s Ctrl+C reaches only the CLI');
        $group->stop(2);
        usleep(100_000);
        $this->assertTrue($group->done());
        $this->assertFalse(posix_kill($child, 0), 'no grandchild outlives the stop');
    }

    public function testTheAppNeverSeesThePlatformCredentials(): void
    {
        $env = DevRun::childEnv(['PATH' => '/bin', 'LINGARA_PLATFORM_CLIENT_SECRET' => 'lgr_cs_x', 'LINGARA_PROFILE' => 'p'], [['PORT', '8787'], ['PATH', '/usr/bin']]);
        $this->assertFalse($env['LINGARA_PLATFORM_CLIENT_SECRET'], 'scrubbed, so symfony/process does not inherit it');
        $this->assertSame(['PATH' => '/usr/bin', 'LINGARA_PLATFORM_CLIENT_SECRET' => false, 'LINGARA_PROFILE' => 'p', 'PORT' => '8787'], $env);
    }
}
