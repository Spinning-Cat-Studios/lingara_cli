<?php

declare(strict_types=1);

namespace Lingara\Cli\Dev;

use Symfony\Component\HttpClient\HttpClient;

/**
 * The waits `apps dev` polls (ADR 16.10.26ae D7 step 4, D8): the app's port
 * accepting a connection, and a GET to the tunnel's metrics address or its
 * probe URL, through symfony/http-client outside the platform transport.
 */
final class Probe
{
    /** Whether `127.0.0.1:$port` accepts a connection now. */
    public static function accepts(int $port): bool
    {
        $socket = @fsockopen('127.0.0.1', $port, $errno, $error, 0.25);
        if ($socket === false) {
            return false;
        }
        fclose($socket);
        return true;
    }

    /** @return array{int, string}|null a GET's status and body, or null when nothing answered within 2 s */
    public static function get(string $url): ?array
    {
        try {
            $response = HttpClient::create(['max_redirects' => 0, 'timeout' => 2, 'max_duration' => 2])->request('GET', $url);
            return [$response->getStatusCode(), $response->getContent(false)];
        } catch (\Throwable) {
            return null;
        }
    }

    /** A free loopback port, for cloudflared's metrics server. */
    public static function freePort(): int
    {
        $server = @stream_socket_server('tcp://127.0.0.1:0');
        if ($server === false) {
            return 0;
        }
        $port = (int) substr((string) strrchr((string) stream_socket_get_name($server, false), ':'), 1);
        fclose($server);
        return $port;
    }
}
