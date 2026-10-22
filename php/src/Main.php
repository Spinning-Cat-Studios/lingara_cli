<?php

declare(strict_types=1);

namespace Lingara\Cli;

use Lingara\Cli\Console\Runner;
use Lingara\Cli\Core\App;
use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\Env;
use Lingara\Cli\Core\StdioTerminal;
use Lingara\Cli\Dev\Signals;
use Lingara\Cli\Http\Http;
use Lingara\Cli\Http\SymfonyTransport;
use Lingara\Cli\Http\SystemClock;
use Lingara\Cli\Store\ConfigDir;
use Lingara\Cli\Store\FileStore;
use Lingara\Cli\Store\Stores;
use Symfony\Component\Process\Process;

/**
 * `lingara`, the PHP kit (ADR 16.10.26af): the production app, one run of
 * argv, and its exit code. Every behaviour a developer or CI job can observe
 * is `conformance/CONTRACT.md`'s; this package is one way of keeping it.
 */
final class Main
{
    /** @param list<string> $argv without the script's name */
    public static function main(array $argv): int
    {
        $app = self::production(Env::fromProcess());
        Signals::install($app->extensionLoaded);
        $outcome = (new Runner($app))->run($argv, static function (string $text): void {
            fwrite(STDERR, $text);
        });
        fwrite(STDOUT, $outcome->stdout);
        return $outcome->exit->value;
    }

    public static function production(Env $env): App
    {
        $endpoints = Assets::endpoints();
        $bases = ['api' => $endpoints['api_url'], 'portal' => $env->get('LINGARA_PLATFORM_URL') ?? $endpoints['platform_url']];
        $http = new Http(new SymfonyTransport(), new SystemClock(), $bases, self::userAgent(), self::acceptLanguage($env));
        $stores = new Stores(Stores::modeOf($env->get('LINGARA_KEYRING')), new FileStore(ConfigDir::path($env)));
        $app = new App($http, $env, $stores, new StdioTerminal());
        $noBrowser = $env->get('LINGARA_NO_BROWSER') === '1';
        $app->browser = static fn (string $url): bool => !$noBrowser && self::open($url);
        return $app;
    }

    /** `lingara-cli/<semver> (php; <os>)`, contract B5. */
    public static function userAgent(): string
    {
        $os = ['Darwin' => 'macos', 'Windows' => 'windows', 'Linux' => 'linux'][PHP_OS_FAMILY] ?? strtolower(PHP_OS);
        return 'lingara-cli/' . Version::VERSION . ' (php; ' . preg_replace('/[^a-z0-9_]/', '_', $os) . ')';
    }

    /** `LC_ALL`, else `LC_MESSAGES`, else `LANG`, as BCP 47: `en_US.UTF-8` is `en-US`; `C` and `POSIX` name no language. */
    public static function acceptLanguage(Env $env): ?string
    {
        $raw = $env->get('LC_ALL') ?? $env->get('LC_MESSAGES') ?? $env->get('LANG');
        $tag = $raw === null ? '' : str_replace('_', '-', substr($raw, 0, strcspn($raw, '.@')));
        return in_array($tag, ['', 'C', 'POSIX'], true) ? null : $tag;
    }

    /** The platform's opener; false when there is none or it failed. */
    private static function open(string $url): bool
    {
        $command = match (PHP_OS_FAMILY) {
            'Darwin' => ['open', $url],
            'Windows' => ['cmd', '/c', 'start', '', $url],
            default => ['xdg-open', $url],
        };
        try {
            return (new Process($command))->run() === 0;
        } catch (\Throwable) {
            return false;
        }
    }
}
