<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

/** The time a run reads and waits on, so tests never sleep. */
interface Clock
{
    /** Seconds since the epoch. */
    public function now(): int;

    public function sleep(int $secs): void;
}
