<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Ctx;

/**
 * One `apps dev` session's moving parts (ADR 16.10.26ae D7): the plan made
 * offline, the session the open minted, the children, what the dev version
 * was pointed at, and whether the session ended under us (taken over,
 * expired), in which case step 8 sends no `DELETE`.
 *
 * The plan: `{name, id, dir, port, start, render_path, cloudflared?: path,
 * fixed?: url, take_over, client, env: list<[k, v]>, write_env?, show_env,
 * probe?}`; the session: `DevApi::open`'s answer.
 */
final class RunState
{
    private const APP_PREFIX = 'app │ ';

    public ?Group $app = null;

    public ?Tunnel $tunnel = null;

    /** @var list<string> */
    public array $urls = [];

    public bool $lost = false;

    public int $respawns = 0;

    /** @var array<string, string> the app's unfinished last line, per stream */
    private array $partial = ['out' => '', 'err' => ''];

    /**
     * @param array<string, mixed> $plan
     * @param array{id: string, lease_secs: int, expires_at: string, app_secret: string, client_secret: ?string} $session
     */
    public function __construct(public readonly Ctx $ctx, public readonly array $plan, public readonly array $session)
    {
    }

    /** Whether Ctrl+C or SIGTERM stopped the session; every wait asks it, so it relays the app's output too. */
    public function stopped(): bool
    {
        $this->pump();
        return Signals::stopped();
    }

    /** The app's output, relayed to stderr line by line after `app │ `, and cloudflared's kept; every wait loop calls this. */
    public function pump(): void
    {
        $app = $this->app;
        if ($app !== null) {
            $this->relay('out', $app->output());
            $this->relay('err', $app->errors());
        }
        $this->tunnel?->drain();
    }

    private function relay(string $stream, string $text): void
    {
        $parts = explode("\n", ($this->partial[$stream] ?? '') . $text);
        $this->partial[$stream] = (string) array_pop($parts);
        foreach ($parts as $line) {
            $this->ctx->out->note(self::APP_PREFIX . $line);
        }
    }

    /** Sleeps `$secs`, a tick at a time, pumping, unless the session is stopped. */
    public function wait(float $secs): void
    {
        $until = microtime(true) + $secs;
        while (microtime(true) < $until && !$this->stopped()) {
            usleep(100_000);
        }
    }
}
