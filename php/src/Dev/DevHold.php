<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Assets;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;

/**
 * Holding an `apps dev` session (ADR 16.10.26ae D7 steps 5–7): bring the
 * tunnel up, point the dev version at it, then renew the lease every
 * `lease_secs / 3` until Ctrl+C, the app's exit or a lost session ends it.
 * A cloudflared that dies is respawned up to five times, 1 s, 2 s, 4 s, 8 s
 * and 16 s apart, and each new URL is pointed at; a sixth death, or a first
 * incarnation that never comes up, is `tunnel_failed`.
 */
final class DevHold
{
    private const BACKOFF_SECS = [1, 2, 4, 8, 16];

    public static function interrupted(): Fail
    {
        return new Fail(ExitCode::Interrupted, 'interrupted', 'interrupted');
    }

    public static function lost(string $name): Fail
    {
        return Fail::message(ExitCode::DevSessionLost, 'dev_session_lost', ['name' => $name]);
    }

    private static function tunnelFailed(RunState $run, string $reason, string $tail): Fail
    {
        $fail = Fail::message(ExitCode::TunnelFailed, 'tunnel_failed', ['name' => (string) $run->plan['name'], 'reason' => $reason]);
        foreach (array_filter(explode("\n", $tail), static fn (string $l): bool => $l !== '') as $line) {
            $fail->hint($line);
        }
        return $fail;
    }

    /** Step 6: `PATCH …/dev {render_url, session_id, draft_version}`, then the ready line. A `dev_session_held` here means a take-over since the open. */
    private static function point(RunState $run, string $url): void
    {
        $body = static fn (mixed $draft): array => ['render_url' => $url, 'session_id' => $run->session['id'], 'draft_version' => $draft];
        try {
            DevApi::patchWithRetry($run->ctx, (string) $run->plan['id'], $body);
        } catch (Fail $fail) {
            if ($fail->exit === ExitCode::DevSessionHeld) {
                $run->lost = true;
                throw self::lost((string) $run->plan['name']);
            }
            throw $fail;
        }
        $run->ctx->out->note(Assets::message('apps_dev_ready', ['name' => (string) $run->plan['name'], 'url' => $url]));
        $run->urls[] = $url;
    }

    /** One incarnation, up: its URL; or, when it did not come up, null and its last lines. */
    private static function incarnation(RunState $run, string &$tail): ?string
    {
        $tunnel = Tunnel::spawn((string) $run->plan['cloudflared'], (int) $run->plan['port']);
        $run->tunnel = $tunnel;
        $stopped = $run->stopped(...);
        $host = $tunnel->hostname($stopped);
        if ($host !== null && $tunnel->ready($host, $run->plan['probe'], $stopped)) {
            return "https://{$host}{$run->plan['render_path']}";
        }
        $tail = $tunnel->tail();
        $tunnel->stop();
        $run->tunnel = null;
        return null;
    }

    /** Steps 5 and 6 for the first incarnation, or the fixed `--render-url`. */
    public static function firstUrl(RunState $run): void
    {
        if (isset($run->plan['fixed'])) {
            self::point($run, (string) $run->plan['fixed']);
            return;
        }
        $tail = '';
        $url = self::incarnation($run, $tail);
        if ($run->stopped()) {
            throw self::interrupted();
        }
        self::point($run, $url ?? throw self::tunnelFailed($run, 'no URL, or no answer through it, within 30 s', $tail));
    }

    /** Step 7's respawn: until an incarnation is up and pointed at, or a sixth death. */
    private static function respawn(RunState $run): void
    {
        $tail = $run->tunnel?->tail() ?? '';
        $run->tunnel?->stop();
        $run->tunnel = null;
        while (true) {
            $secs = self::BACKOFF_SECS[$run->respawns] ?? throw self::tunnelFailed($run, 'cloudflared died a sixth time', $tail);
            $run->wait($secs);
            if ($run->stopped()) {
                throw self::interrupted();
            }
            $run->respawns++;
            $url = self::incarnation($run, $tail);
            if ($run->stopped()) {
                throw self::interrupted();
            }
            if ($url !== null) {
                self::point($run, $url);
                return;
            }
        }
    }

    /** One scheduled renew. A refusal that means the session is over ends the run with no `DELETE`; anything else waits for the next scheduled renew (B5). */
    private static function renew(RunState $run): void
    {
        try {
            DevApi::renew($run->ctx, (string) $run->plan['id'], $run->session['id']);
        } catch (Fail $fail) {
            if ($fail->exit === ExitCode::DevSessionLost) {
                $run->lost = true;
                throw self::lost((string) $run->plan['name']);
            }
        }
    }

    /** Step 7: the loop, until the first thing that ends the session. */
    public static function hold(RunState $run): void
    {
        $period = max(1, intdiv($run->session['lease_secs'], 3));
        $nextRenew = microtime(true) + $period;
        while (true) {
            if ($run->stopped()) {
                throw self::interrupted();
            }
            if ($run->app !== null && $run->app->done()) {
                DevRun::appResult($run);
                return;
            }
            if ($run->tunnel !== null && !$run->tunnel->alive()) {
                self::respawn($run);
            }
            if (microtime(true) >= $nextRenew) {
                self::renew($run);
                $nextRenew = microtime(true) + $period;
            }
            usleep(100_000);
        }
    }
}
