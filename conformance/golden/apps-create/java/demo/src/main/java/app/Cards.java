package app;

import com.getlingara.apps.Card;
import com.getlingara.apps.Item;
import com.getlingara.apps.Messages;
import java.util.Map;

/** Demo's card, in the learner's language. */
final class Cards {
  private Cards() {}

  // One catalogue, checked at startup: a key missing in a locale falls back to the default's
  // text. Add a locale by adding its strings.
  static final Messages MESSAGES =
      Messages.of(
          "en",
          Map.of(
              "en", Map.of("heading", "Today's five", "review", "Review 3 words", "done", "Done ({streak})"),
              "zh-Hans", Map.of("heading", "今日五词", "review", "复习 3 个词", "done", "完成（{streak}）")));

  // The learner's UI language is the request's locale, never the languages slice.
  static Card localisedCard(String locale, int streak) {
    Messages.Lookup t = MESSAGES.forLocale(locale);
    return Card.card()
        .heading(t.t("heading"), 1)
        .term("雨", "yǔ", "rain", "zh")
        .list(Item.text(t.t("review")), Item.term("二", "èr", "two", "zh"))
        .button(t.t("done", Map.of("streak", Integer.toString(streak))), "done")
        .build();
  }
}
