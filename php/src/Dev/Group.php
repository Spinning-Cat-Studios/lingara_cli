<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Symfony\Component\Process\Process;

/**
 * A child `apps dev` runs (ADR 16.10.26ae D5, D7 steps 4 and 8; ADR
 * 16.10.26af D2: symfony/process). Each child leads its own process group,
 * so a terminal's Ctrl+C reaches only the CLI, and a stop signals the whole
 * group: SIGTERM, then SIGKILL once the grace period passes. symfony/process
 * cannot start a child in a new group, so the child is started through a
 * one-line PHP launcher that calls `setpgid(0, 0)` and then execs the real
 * command in its own pid: the reason `apps dev` needs ext-pcntl and
 * ext-posix on Unix.
 */
final class Group
{
    /** `pcntl_exec` returns only when it failed: the shell's "command not found" status. */
    private const LAUNCHER = 'posix_setpgid(0, 0); $a = array_slice($argv, 1); @pcntl_exec((string) array_shift($a), $a, getenv()); exit(127);';

    private function __construct(public readonly Process $process)
    {
    }

    /**
     * @param list<string> $command an absolute program, then its args
     * @param array<string, string|false>|null $env false removes a variable the CLI's own environment has
     */
    public static function spawn(array $command, ?string $cwd, ?array $env): self
    {
        $program = self::which(array_shift($command) ?? '');
        $process = new Process([PHP_BINARY, '-r', self::LAUNCHER, '--', $program, ...$command], $cwd, $env, null, null);
        $process->start();
        return new self($process);
    }

    /** `pcntl_exec` searches no `PATH`, so a bare program name (`sh`) is found here. */
    private static function which(string $program): string
    {
        if (str_contains($program, '/')) {
            return $program;
        }
        foreach (explode(PATH_SEPARATOR, (string) getenv('PATH')) as $dir) {
            if ($dir !== '' && is_executable("{$dir}/{$program}")) {
                return "{$dir}/{$program}";
            }
        }
        return $program;
    }

    public function done(): bool
    {
        return !$this->process->isRunning();
    }

    /** The exit status, or null when a signal ended it (or it still runs). */
    public function status(): ?int
    {
        return $this->done() && !$this->process->hasBeenSignaled() ? $this->process->getExitCode() : null;
    }

    /** What the child wrote to stdout and stderr since the last call. */
    public function output(): string
    {
        return $this->process->getIncrementalOutput();
    }

    public function errors(): string
    {
        return $this->process->getIncrementalErrorOutput();
    }

    private function signalGroup(int $signal): void
    {
        $pid = $this->process->getPid();
        if ($pid !== null && !@posix_kill(-$pid, $signal)) {
            // The launcher may not have reached setpgid yet: signal the child itself.
            @posix_kill($pid, $signal);
        }
    }

    /** Signals the whole group: SIGTERM, then SIGKILL once `$graceSecs` passes. The group may outlive its leader. */
    public function stop(float $graceSecs): void
    {
        $this->signalGroup(SIGTERM);
        $until = microtime(true) + $graceSecs;
        while (!$this->done() && microtime(true) < $until) {
            usleep(50_000);
        }
        $this->signalGroup(SIGKILL);
        $until = microtime(true) + 1;
        while (!$this->done() && microtime(true) < $until) {
            usleep(20_000);
        }
    }
}
