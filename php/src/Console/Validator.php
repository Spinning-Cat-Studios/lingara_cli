<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Lingara\Cli\Core\Fail;

/**
 * What symfony/console's untyped inputs cannot say, checked from the
 * kit-local table before anything runs (ADR 16.10.26af D2, D9): a required
 * flag, an `enum`'s values, an `int` port's range and the schema's
 * `conflicts`. Each refusal is `usage` (2) and reaches no server (contract
 * B2), as clap's and oclif's parse errors do in the reference kits.
 */
final class Validator
{
    /** @param array<string, mixed> $row */
    public static function check(array $row, Input $input): void
    {
        foreach (TableCommand::rows($row['flags'] ?? []) as $flag) {
            self::flag($flag, $input);
        }
        foreach (TableCommand::rows($row['conflicts'] ?? []) as $pair) {
            [$a, $b] = array_map('strval', array_values($pair));
            if ($input->given($a) && $input->given($b)) {
                throw Fail::usage("--{$a} cannot be used with --{$b}");
            }
        }
    }

    /** @param array<string, mixed> $flag */
    private static function flag(array $flag, Input $input): void
    {
        $name = (string) $flag['name'];
        if (($flag['required'] ?? false) === true && !$input->given($name)) {
            throw Fail::usage("the required flag --{$name} was not given");
        }
        $values = ($flag['repeatable'] ?? false) === true ? $input->many($name) : array_filter([$input->opt($name)], 'is_string');
        foreach ($values as $value) {
            self::value($flag, $name, $value);
        }
    }

    /** @param array<string, mixed> $flag */
    private static function value(array $flag, string $name, string $value): void
    {
        $allowed = is_array($flag['values'] ?? null) ? $flag['values'] : null;
        if ($flag['type'] === 'enum' && $allowed !== null && !in_array($value, $allowed, true)) {
            throw Fail::usage("invalid value '{$value}' for --{$name}: one of " . implode(', ', $allowed));
        }
        $port = ctype_digit($value) ? (int) $value : 0;
        if ($flag['type'] === 'int' && ($port < 1 || $port > 65535)) {
            throw Fail::usage("invalid value '{$value}' for --{$name}: a whole number from 1 to 65535");
        }
    }
}
