package app

import com.getlingara.apps.kotlin.LingaraApp
import com.getlingara.apps.kotlin.lingaraApp
import java.util.concurrent.ConcurrentHashMap

/** The app: one render and one action. [secrets] is LINGARA_APP_SECRET split on commas. */
fun createApp(vararg secrets: String): LingaraApp {
    // Per-learner state is keyed on subject, the value the app's events carry too.
    val streaks = ConcurrentHashMap<String, Int>()
    return lingaraApp {
        secrets(*secrets)
        render { request -> localisedCard(request.locale, streaks.getOrDefault(request.subject, 0)) }
        // The button's action; it may arrive twice, so make it safe to repeat.
        action("done") { request -> localisedCard(request.locale, streaks.merge(request.subject, 1, Int::plus) ?: 1) }
    }
}
