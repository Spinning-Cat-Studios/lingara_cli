<?php

declare(strict_types=1);

namespace Lingara\Cli\Console;

use Symfony\Component\Console\Input\InputInterface;

/** A parsed command line, read by name through the types the table gave each input. */
final class Input
{
    public function __construct(private readonly InputInterface $input)
    {
    }

    /** A positional arg or a flag's value, or null when absent. */
    public function opt(string $name): ?string
    {
        $value = $this->raw($name);
        return is_string($value) ? $value : null;
    }

    /** A required arg or flag's value. */
    public function str(string $name): string
    {
        return $this->opt($name) ?? '';
    }

    /** @return list<string> a repeatable flag's values, in argv order */
    public function many(string $name): array
    {
        $value = $this->raw($name);
        return is_array($value) ? array_values(array_filter($value, 'is_string')) : [];
    }

    public function flag(string $name): bool
    {
        return $this->raw($name) === true;
    }

    /** An `int` flag, already checked by `Validator`. */
    public function int(string $name): ?int
    {
        $value = $this->opt($name);
        return $value === null ? null : (int) $value;
    }

    /** @return array{yes: bool, confirm: ?string} the confirm group */
    public function confirm(): array
    {
        return ['yes' => $this->flag('yes'), 'confirm' => $this->opt('confirm')];
    }

    /** Whether argv gave this input at all: a set bool, a value, or any repeat. */
    public function given(string $name): bool
    {
        $value = $this->raw($name);
        return $value !== null && $value !== false && $value !== [];
    }

    private function raw(string $name): mixed
    {
        if ($this->input->hasArgument($name)) {
            return $this->input->getArgument($name);
        }
        return $this->input->hasOption($name) ? $this->input->getOption($name) : null;
    }
}
