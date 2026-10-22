<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

/** One request before it is spelled: its route, path variables, query and body. */
final class Request
{
    /**
     * @param array<string, string> $vars
     * @param list<array{string, string}> $query
     * @param list<array{string, string}>|null $form
     */
    public function __construct(
        public readonly Route $route,
        public readonly array $vars = [],
        public readonly array $query = [],
        public readonly mixed $json = null,
        public readonly ?array $form = null,
    ) {
    }

    /** @param array<string, string> $vars */
    public static function json(Route $route, array $vars, mixed $body): self
    {
        return new self($route, $vars, [], $body);
    }

    /** @param list<array{string, string}> $pairs */
    public static function form(Route $route, array $pairs): self
    {
        return new self($route, [], [], null, $pairs);
    }
}
