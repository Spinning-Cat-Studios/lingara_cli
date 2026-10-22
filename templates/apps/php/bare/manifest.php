<?php

declare(strict_types=1);

// The manifest as code. `lingara apps update` runs `php manifest.php` and
// uploads what this prints, so print nothing else.

require __DIR__ . '/vendor/autoload.php';

use Lingara\Apps\Generated\AppSlotName;
use Lingara\Apps\Manifest;

$json = Manifest::create()
    ->defaultLocale('en')
    ->name(['en' => '{{display_name}}', 'zh-Hans' => '{{display_name}}'])
    ->description(['en' => '{{description}}', 'zh-Hans' => '{{description}}'])
    // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
    ->renderUrl('https://dev.invalid/')
    ->slots(...array_map(AppSlotName::from(...), {{slots}}))
    ->context(...{{context}})
    ->toJson(); // a ManifestException names the rule the upload would refuse
echo $json, "\n";
