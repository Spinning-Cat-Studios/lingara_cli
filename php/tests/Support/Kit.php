<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests\Support;

use Lingara\Cli\Console\Runner;
use Lingara\Cli\Core\App;
use Lingara\Cli\Core\Env;
use Lingara\Cli\Core\Outcome;
use Lingara\Cli\Http\Http;
use Lingara\Cli\Store\FileStore;
use Lingara\Cli\Store\Stores;

/** An app over the fakes, in a scratch config dir, and one run of argv against it. */
final class Kit
{
    public string $stderr = '';

    public readonly App $app;

    public readonly Runner $runner;

    /**
     * @param array<string, string> $env
     * @param array<string, bool> $extensions what the `extension_loaded` seam answers; unnamed ones are loaded
     */
    public function __construct(public readonly FakeTransport $transport, public readonly FakeTerminal $terminal, array $env = [], array $extensions = [])
    {
        $env['LINGARA_CONFIG_DIR'] ??= sys_get_temp_dir() . '/lingara-php-test-' . bin2hex(random_bytes(6));
        $http = new Http($transport, new FakeClock(), ['api' => 'http://api.test', 'portal' => 'http://portal.test'], 'lingara-cli/0.0.0 (php; test)', 'en-US');
        $stores = new Stores(Stores::modeOf($env['LINGARA_KEYRING'] ?? null), new FileStore($env['LINGARA_CONFIG_DIR']));
        $this->app = new App($http, new Env($env), $stores, $terminal);
        $this->app->extensionLoaded = static fn (string $ext): bool => $extensions[$ext] ?? true;
        $this->runner = new Runner($this->app);
    }

    /** @param list<string> $argv */
    public function run(array $argv): Outcome
    {
        $this->stderr = '';
        return $this->runner->run($argv, function (string $text): void {
            $this->stderr .= $text;
        });
    }

    /** A stored, fresh profile record, as `credentials: stored` seeds it. */
    public function signIn(string $email = 'dev@conformance.invalid'): void
    {
        $record = ['developer_id' => 'd1', 'email' => $email, 'scopes' => 'platform:read', 'refresh_token' => 'lgr_rt_x', 'access_token' => 'lgr_at_x', 'access_expires_at' => '2099-01-01T00:00:00Z'];
        $this->app->stores->file->save('default', $record);
    }
}
