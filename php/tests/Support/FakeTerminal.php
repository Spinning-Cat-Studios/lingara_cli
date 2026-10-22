<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests\Support;

use Lingara\Cli\Core\Terminal;

/** A terminal the ritual's tests type into. */
final class FakeTerminal implements Terminal
{
    /** @param list<string> $lines */
    public function __construct(public bool $tty = false, public array $lines = [])
    {
    }

    public function interactive(): bool
    {
        return $this->tty;
    }

    public function readLine(): ?string
    {
        return array_shift($this->lines);
    }
}
