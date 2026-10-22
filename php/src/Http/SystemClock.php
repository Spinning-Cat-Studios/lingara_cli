<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

final class SystemClock implements Clock
{
    public function now(): int
    {
        return time();
    }

    public function sleep(int $secs): void
    {
        if ($secs > 0) {
            sleep($secs);
        }
    }
}
