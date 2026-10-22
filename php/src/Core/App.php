<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

use Lingara\Cli\Auth\Session;
use Lingara\Cli\Http\Http;
use Lingara\Cli\Store\ConfigDir;
use Lingara\Cli\Store\Stores;

/**
 * One process's world: the HTTP client, the environment, the credential
 * store, the terminal and the seams a test replaces. It outlives a single
 * run, because canvas apply's inner verbs run in-process through `run` and
 * share this app's signed-in session, so no second token exchange happens
 * (ADR 16.10.26ad D7 step 4).
 */
final class App
{
    public ?Session $session = null;

    /** @var \Closure(list<string>, \Closure(string): void): Outcome the in-process entry point (D7) */
    public \Closure $run;

    /** @var \Closure(): array<string, mixed> `lingara __tree`'s projection of the registered commands */
    public \Closure $tree;

    /** @var \Closure(string): bool opens a URL in the browser; false when it could not */
    public \Closure $browser;

    /** @var \Closure(string): bool `extension_loaded`, a seam so a test can take ext-pcntl away (ADR 16.10.26af D2) */
    public \Closure $extensionLoaded;

    /** @var array{client_id: string, api_url: string, platform_url: string} where production is: `endpoints.json` */
    public readonly array $endpoints;

    public readonly string $configDir;

    public function __construct(public readonly Http $http, public readonly Env $env, public readonly Stores $stores, public readonly Terminal $terminal)
    {
        $this->endpoints = Assets::endpoints();
        $this->configDir = ConfigDir::path($env);
        $this->browser = static fn (string $url): bool => false;
        $this->extensionLoaded = extension_loaded(...);
        // Both are the runner's, which sets them as it is built around this app.
        $this->run = static fn (array $argv, \Closure $err): Outcome => new Outcome(ExitCode::Failure, '');
        $this->tree = static fn (): array => [];
    }
}
