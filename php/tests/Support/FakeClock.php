<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests\Support;

use Lingara\Cli\Http\Clock;

/** Time that moves only when a test sleeps. */
final class FakeClock implements Clock
{
    public function __construct(public int $at = 1_760_000_000)
    {
    }

    public function now(): int
    {
        return $this->at;
    }

    public function sleep(int $secs): void
    {
        $this->at += $secs;
    }
}
