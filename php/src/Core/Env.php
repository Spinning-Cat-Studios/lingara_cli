<?php

declare(strict_types=1);

namespace Lingara\Cli\Core;

/** The process environment, read once at start-up, so tests pass their own. */
final class Env
{
    /** @param array<string, string> $vars */
    public function __construct(private readonly array $vars)
    {
    }

    public static function fromProcess(): self
    {
        $vars = [];
        foreach (getenv() as $key => $value) {
            $vars[(string) $key] = (string) $value;
        }
        return new self($vars);
    }

    /** A set, non-empty variable, or null. */
    public function get(string $name): ?string
    {
        $value = $this->vars[$name] ?? '';
        return $value === '' ? null : $value;
    }

    /** @return array<string, string> every variable: the environment `apps dev`'s child starts from */
    public function all(): array
    {
        return $this->vars;
    }
}
