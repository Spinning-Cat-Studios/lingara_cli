<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

/**
 * The one seam every request crosses: symfony/http-client in production, a
 * scripted fake in the unit tests. A thrown `\RuntimeException` is a
 * connection failure, contract B2 rule 7.
 */
interface Transport
{
    /** @param array<string, string> $headers */
    public function send(string $method, string $url, array $headers, string $body): WireResponse;
}
