<?php

declare(strict_types=1);

// Serves Demo on 127.0.0.1 with PHP's own server, on $PORT.

if ((string) getenv('LINGARA_APP_SECRET') === '') {
    fwrite(STDERR, "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`.\n");
    exit(1);
}
$port = (string) (getenv('PORT') ?: '8787');
echo "demo is listening on http://127.0.0.1:{$port}/\n";
passthru(escapeshellarg(PHP_BINARY) . ' -S ' . escapeshellarg("127.0.0.1:{$port}") . ' ' . escapeshellarg(__DIR__ . '/public/index.php'), $status);
exit($status);
