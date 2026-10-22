<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/**
 * The terminal the confirmation ritual talks to (ADR 16.10.26ad D5 step 1;
 * contract B3.1). Interactive means stdin **and** stderr are terminals,
 * because prompts go to stderr: `lingara apps delete x > out.json` still
 * prompts.
 */
interface Terminal
{
    public function interactive(): bool;

    /** One line from stdin, null at end of input. */
    public function readLine(): ?string;
}
