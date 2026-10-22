<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests\Support;

use Lingara\Cli\Http\Transport;
use Lingara\Cli\Http\WireResponse;

/** A scripted platform: each request takes the next answer, and every request is kept. */
final class FakeTransport implements Transport
{
    /** @var list<array{method: string, url: string, headers: array<string, string>, body: string}> */
    public array $sent = [];

    /** @param list<WireResponse> $answers */
    public function __construct(public array $answers = [])
    {
    }

    public function send(string $method, string $url, array $headers, string $body): WireResponse
    {
        $this->sent[] = ['method' => $method, 'url' => $url, 'headers' => $headers, 'body' => $body];
        return array_shift($this->answers) ?? new WireResponse(599, [], '');
    }

    public static function json(int $status, string $body): WireResponse
    {
        return new WireResponse($status, ['content-type' => 'application/json'], $body);
    }
}
