<?php

declare(strict_types=1);

namespace Lingara\Cli\Table;

/**
 * The flags several commands share, as the kit-local table spells them
 * (ADR 16.10.26af D2: symfony/console's inputs are untyped strings, so
 * `type`, `values` and an option's `required` live here, read by both
 * registration and `__tree`). A row is `name`, `type` (`string`, `bool`,
 * `int`, `path`, `enum`), then optional `short`, `required`, `repeatable`,
 * `values`, `value` (the help's value name) and `help`.
 */
final class Flags
{
    /** `--json` and `--profile`, on every command (16.10.26ab D2). */
    public const GLOBAL = [
        ['name' => 'json', 'type' => 'bool', 'help' => 'Machine-readable output on stdout'],
        ['name' => 'profile', 'type' => 'string', 'value' => 'name', 'help' => 'The stored credentials to use'],
    ];

    /** The `confirm` group every dangerous command carries (D5). Neither flag reads the environment. */
    public const CONFIRM = [
        ['name' => 'yes', 'type' => 'bool', 'short' => 'y', 'help' => 'Answer the y/n question; needs --confirm too'],
        ['name' => 'confirm', 'type' => 'string', 'value' => 'email', 'help' => 'Your developer email, typed back; needs -y too'],
    ];

    /** Every object `delete`: an optional version, then the ritual. */
    public const DELETE = [
        ['name' => 'version', 'type' => 'string', 'value' => 'semver', 'help' => 'Delete only this version'],
        ...self::CONFIRM,
    ];

    /** `--config`: which platform.toml, before `LINGARA_PLATFORM_TOML` (16.10.26ae D4). */
    public const CONFIG = ['name' => 'config', 'type' => 'path', 'value' => 'path', 'help' => 'The platform.toml to read; default the nearest at or above this directory'];

    public const ID = [['name' => 'id', 'type' => 'string', 'required' => true]];
}
