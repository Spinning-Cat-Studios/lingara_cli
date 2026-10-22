<?php

declare(strict_types=1);

namespace App;

use Lingara\Apps\App;
use Lingara\Apps\Generated\AppActionRequest;
use Lingara\Apps\Generated\AppRenderRequest;
use Lingara\Apps\Generated\Card as CardModel;

/** The app: one render and one action, keyed on the learner. */
final class Apps
{
    /** @param list<string> $secrets LINGARA_APP_SECRET split on commas: two during a rotation */
    public static function create(array $secrets): App
    {
        // A per-process store; hold per-learner state on `subject`, the value your events carry.
        $streaks = [];
        return new App(
            secret: $secrets,
            render: static fn(AppRenderRequest $request) => Cards::localised($request->getLocale(), $streaks[$request->getSubject()] ?? 0),
            actions: [
                // The button's `action`; may arrive twice, so make it safe to repeat.
                'done' => static function (AppActionRequest $request) use (&$streaks): CardModel {
                    $streaks[$request->getSubject()] = ($streaks[$request->getSubject()] ?? 0) + 1;
                    return Cards::localised($request->getLocale(), $streaks[$request->getSubject()]);
                },
            ],
        );
    }
}
