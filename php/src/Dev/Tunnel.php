<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Lingara\Cli\Core\Json;

/**
 * One cloudflared incarnation (ADR 16.10.26ae D8). **URL**: the CLI picks a
 * free loopback port for `--metrics` and polls its `/quicktunnel`, falling
 * back to the `https://…trycloudflare.com` line on stderr. **Ready**: the
 * probe URL (`LINGARA_TUNNEL_PROBE_URL`, default `https://{hostname}/`)
 * answers anything but Cloudflare's `530`. Each wait is 30 s, and each gives
 * up early once the session is stopped.
 */
final class Tunnel
{
    public const WAIT_SECS = 30;
    private const KEPT_LINES = 20;
    private const DEFAULT_PROBE = 'https://{hostname}/';

    /** @var list<string> the last lines cloudflared wrote */
    private array $lines = [];

    private string $partial = '';

    private ?string $loggedHost = null;

    private function __construct(private readonly Group $group, public readonly string $metrics)
    {
    }

    /** `cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>`. */
    public static function spawn(string $binary, int $port): self
    {
        $metrics = '127.0.0.1:' . Probe::freePort();
        return new self(Group::spawn([$binary, 'tunnel', '--url', "http://127.0.0.1:{$port}", '--metrics', $metrics], null, null), $metrics);
    }

    /** Reads what cloudflared wrote since the last look; its stdout is not kept. */
    public function drain(): void
    {
        $this->group->output();
        $text = $this->partial . $this->group->errors();
        $parts = explode("\n", $text);
        $this->partial = (string) array_pop($parts);
        foreach ($parts as $line) {
            $this->loggedHost ??= Cloudflared::hostnameIn($line);
            $this->lines[] = $line;
        }
        $this->lines = array_slice($this->lines, -self::KEPT_LINES);
    }

    public function alive(): bool
    {
        return !$this->group->done();
    }

    /** The last lines cloudflared wrote, for `tunnel_failed`'s reason. */
    public function tail(): string
    {
        $this->drain();
        return implode("\n", $this->lines);
    }

    private function quicktunnel(): ?string
    {
        $answer = Probe::get("http://{$this->metrics}/quicktunnel");
        $host = $answer === null ? null : Json::opt(Json::decode($answer[1]), 'hostname');
        return $host === '' ? null : $host;
    }

    /**
     * The quick tunnel's hostname: metrics first, the log line second.
     *
     * @param \Closure(): bool $stopped
     */
    public function hostname(\Closure $stopped): ?string
    {
        $deadline = microtime(true) + self::WAIT_SECS;
        while (microtime(true) < $deadline && $this->alive() && !$stopped()) {
            $this->drain();
            $host = $this->quicktunnel() ?? $this->loggedHost;
            if ($host !== null) {
                return $host;
            }
            usleep(250_000);
        }
        return null;
    }

    /**
     * Whether the probe reached the app through the tunnel: any answer but a `530`, once a second, for at most 30 s.
     *
     * @param \Closure(): bool $stopped
     */
    public function ready(string $host, ?string $template, \Closure $stopped): bool
    {
        $url = strtr($template ?? self::DEFAULT_PROBE, ['{hostname}' => $host, '{metrics}' => $this->metrics]);
        $deadline = microtime(true) + self::WAIT_SECS;
        while (microtime(true) < $deadline && $this->alive() && !$stopped()) {
            $this->drain();
            $answer = Probe::get($url);
            if ($answer !== null && $answer[0] !== 530) {
                return true;
            }
            usleep(1_000_000);
        }
        return false;
    }

    public function stop(): void
    {
        $this->group->stop(5);
    }
}
