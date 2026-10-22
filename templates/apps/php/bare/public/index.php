<?php

declare(strict_types=1);

// The front controller `php server.php` serves: a PSR-15 handler reads the raw
// body, verifies, dispatches and replies.

require __DIR__ . '/../vendor/autoload.php';

use App\Apps;
use Lingara\Apps\Psr15Handler;
use Nyholm\Psr7\Factory\Psr17Factory;
use Nyholm\Psr7Server\ServerRequestCreator;

$factory = new Psr17Factory();
$app = Apps::create(explode(',', (string) getenv('LINGARA_APP_SECRET')));
$handler = new Psr15Handler($app, $factory, $factory);
$response = $handler->handle((new ServerRequestCreator($factory, $factory, $factory, $factory))->fromGlobals());
http_response_code($response->getStatusCode());
foreach ($response->getHeaders() as $name => $values) {
    header("{$name}: " . implode(', ', $values));
}
echo $response->getBody();
