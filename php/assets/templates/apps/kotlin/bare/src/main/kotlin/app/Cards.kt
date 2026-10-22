package app

import com.getlingara.apps.kotlin.Card
import com.getlingara.apps.kotlin.card
import com.getlingara.apps.kotlin.messages

// One catalogue, checked at startup: a key missing in a locale falls back to the default's text.
// Add a locale by adding its strings.
val cardMessages =
    messages {
        defaultLocale = "en"
        locale("en", "heading" to "Today's five", "review" to "Review 3 words", "done" to "Done ({streak})")
        locale("zh-Hans", "heading" to "今日五词", "review" to "复习 3 个词", "done" to "完成（{streak}）")
    }

// The learner's UI language is the request's locale, never the languages slice.
fun localisedCard(
    locale: String,
    streak: Int,
): Card {
    val t = cardMessages.forLocale(locale)
    return card {
        heading(t.t("heading"), 1)
        term("雨", reading = "yǔ", gloss = "rain", lang = "zh")
        list(item.text(t.t("review")), item.term("二", reading = "èr", gloss = "two", lang = "zh"))
        button(t.t("done", "streak" to "$streak"), "done")
    }
}
