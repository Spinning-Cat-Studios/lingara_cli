<?php

declare(strict_types=1);

namespace Lingara\Cli;

/**
 * The repository's VERSION, committed here because a published package
 * carries only php/ (ADR 16.10.26af D8): `lingara --version` and the
 * User-Agent read it, never ../VERSION. tests/CoreTest.php holds it equal to
 * the repository's file wherever that file is in reach.
 */
final class Version
{
    public const VERSION = '0.1.0-alpha.1';
}
