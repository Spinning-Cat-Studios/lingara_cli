package app;

import com.getlingara.apps.LingaraApp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The app: one render and one action, keyed on the learner. */
public final class Apps {
  private Apps() {}

  /** {@code secrets} is LINGARA_APP_SECRET split on commas: two during a rotation. */
  public static LingaraApp create(String... secrets) {
    // Per-learner state is keyed on subject, the value the app's events carry too.
    Map<String, Integer> streaks = new ConcurrentHashMap<>();
    return LingaraApp.builder()
        .secrets(secrets)
        .render(request -> Cards.localisedCard(request.getLocale(), streaks.getOrDefault(request.getSubject(), 0)))
        // The button's action; it may arrive twice, so make it safe to repeat.
        .action("done", request -> Cards.localisedCard(request.getLocale(), streaks.merge(request.getSubject(), 1, Integer::sum)))
        .build();
  }
}
