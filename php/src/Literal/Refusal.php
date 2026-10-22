<?php

declare(strict_types=1);

namespace Lingara\Cli\Literal;

/**
 * Why `lingara/connection.js` is refused (spec/connection-literal.md): a
 * code at a 1-based `[line, column]`, or a scripted statement by handle.
 */
final class Refusal extends \RuntimeException
{
    /** @param array{int, int}|null $pos */
    public function __construct(public readonly string $why, public readonly ?array $pos = null, public readonly ?string $statement = null)
    {
        parent::__construct($why);
    }

    /** @param array{int, int} $pos */
    public static function at(string $code, array $pos): self
    {
        return new self($code, $pos);
    }

    public static function scripted(string $handle): self
    {
        return new self('connection_scripted', null, $handle);
    }
}
