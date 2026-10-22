<?php

declare(strict_types=1);

namespace App;

use Lingara\Apps\Card;
use Lingara\Apps\Generated\Card as CardModel;
use Lingara\Apps\Item;
use Lingara\Apps\Messages;

/** {{display_name}}'s card, in the learner's language. */
final class Cards
{
    // One catalogue, checked at startup: a key missing in a locale falls back to
    // the default's text. Add a locale by adding its strings.
    public static function messages(): Messages
    {
        /** @var Messages|null $messages */
        static $messages = null;
        return $messages ??= Messages::create('en', [
            'en' => ['heading' => "Today's five", 'review' => 'Review 3 words', 'done' => 'Done ({streak})'],
            'zh-Hans' => ['heading' => '今日五词', 'review' => '复习 3 个词', 'done' => '完成（{streak}）'],
        ]);
    }

    // The learner's UI language is the request's `locale`, never the languages slice.
    public static function localised(string $locale, int $streak): CardModel
    {
        $t = self::messages()->for($locale);
        return Card::create()
            ->heading($t->t('heading'), 1)
            ->term('雨', reading: 'yǔ', gloss: 'rain', lang: 'zh')
            ->list([Item::text($t->t('review')), Item::term('二', reading: 'èr')])
            ->button($t->t('done', ['streak' => (string) $streak]), 'done')
            ->build();
    }
}
