import { card, createMessages, item, type Card } from "@lingara/apps";

// One catalogue, checked at startup: a key missing in a locale falls back to
// the default's text. Add a locale by adding its strings.
const messages = createMessages({
  defaultLocale: "en",
  catalogue: {
    en: { heading: "Today's five", review: "Review 3 words", done: "Done ({streak})" },
    "zh-Hans": { heading: "今日五词", review: "复习 3 个词", done: "完成（{streak}）" },
  },
});

// The learner's UI language is the request's `locale`, never the languages slice.
export function localisedCard(locale: string, streak: number): Card {
  const t = messages.for(locale);
  return card()
    .heading(t("heading"), 1)
    .term({ word: "雨", reading: "yǔ", gloss: "rain", lang: "zh" })
    .list([item.text(t("review")), item.term({ word: "二", reading: "èr" })])
    .button(t("done", { streak: String(streak) }), "done")
    .build();
}
