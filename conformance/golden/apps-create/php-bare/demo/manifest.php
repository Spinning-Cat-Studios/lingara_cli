<?php

declare(strict_types=1);

// The manifest as code. `lingara apps update` runs `php manifest.php` and
// uploads what this prints, so print nothing else.

require __DIR__ . '/vendor/autoload.php';

use Lingara\Apps\Generated\AppSlotName;
use Lingara\Apps\Manifest;

$json = Manifest::create()
    ->defaultLocale('en')
    ->name(['en' => 'Demo', 'zh-Hans' => 'Demo'])
    ->description(['en' => 'A Lingara app.', 'zh-Hans' => 'A Lingara app.'])
    // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
    ->renderUrl('https://dev.invalid/')
    ->slots(...array_map(AppSlotName::from(...), ["home.side"]))
    ->context(...[])
    ->toJson(); // a ManifestException names the rule the upload would refuse
echo $json, "\n";
