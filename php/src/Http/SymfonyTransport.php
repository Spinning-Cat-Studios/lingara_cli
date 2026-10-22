<?php

declare(strict_types=1);

namespace Lingara\Cli\Http;

use Symfony\Component\HttpClient\HttpClient;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * symfony/http-client, the de facto PHP client (ADR 16.10.26af D1, D2), never
 * the `lingara` API library, whose User-Agent and retries are not the CLI's.
 * No redirects are followed: an unexpected 3xx is B2 rule 8's to classify.
 */
final class SymfonyTransport implements Transport
{
    private HttpClientInterface $client;

    public function __construct()
    {
        $this->client = HttpClient::create(['max_redirects' => 0, 'timeout' => 60, 'max_duration' => 120]);
    }

    public function send(string $method, string $url, array $headers, string $body): WireResponse
    {
        $options = ['headers' => $headers];
        if ($body !== '') {
            $options['body'] = $body;
        }
        try {
            $response = $this->client->request($method, $url, $options);
            $status = $response->getStatusCode();
            $out = [];
            foreach ($response->getHeaders(false) as $name => $values) {
                $out[strtolower((string) $name)] = implode(', ', $values);
            }
            return new WireResponse($status, $out, $response->getContent(false));
        } catch (ExceptionInterface $e) {
            throw new \RuntimeException($e->getMessage(), 0, $e);
        }
    }
}
