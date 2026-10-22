<?php

declare(strict_types=1);

namespace App\Tests;

use App\Apps;
use Lingara\Apps\Psr15Handler;
use Nyholm\Psr7\Factory\Psr17Factory;
use PHPUnit\Framework\TestCase;

final class RenderTest extends TestCase
{
    // A visibly fake key: the secret is lgr_whsec_ + its base64.
    private const KEY = 'template-offline-test-secret-01!';

    public function testASignedRenderReturnsACard(): void
    {
        $id = 'lgr_msg_0123456789abcdef0123456789abcdef';
        $body = json_encode([
            'type' => 'app.render', 'id' => $id, 'install_id' => '7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f',
            'subject' => 'lgr_sub_template', 'slot' => 'home.side', 'locale' => 'zh-Hans', 'context' => [],
        ], JSON_THROW_ON_ERROR);
        $timestamp = (string) time();
        // Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
        $signature = base64_encode(hash_hmac('sha256', "{$id}.{$timestamp}.{$body}", self::KEY, true));
        $factory = new Psr17Factory();
        $request = $factory->createServerRequest('POST', '/')
            ->withHeader('content-type', 'application/json')
            ->withHeader('webhook-id', $id)
            ->withHeader('webhook-timestamp', $timestamp)
            ->withHeader('webhook-signature', "v1,{$signature}")
            ->withBody($factory->createStream($body));
        $app = Apps::create(['lgr_whsec_' . base64_encode(self::KEY)]);
        $response = (new Psr15Handler($app, $factory, $factory))->handle($request);
        self::assertSame(200, $response->getStatusCode());
        self::assertArrayHasKey('card', json_decode((string) $response->getBody(), true, flags: JSON_THROW_ON_ERROR));
    }
}
