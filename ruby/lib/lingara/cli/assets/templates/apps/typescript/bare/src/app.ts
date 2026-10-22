import { lingaraApp, type LingaraApp } from "@lingara/apps";

import { localisedCard } from "./card.js";

/**
 * The app: one render and one action. `secret` is LINGARA_APP_SECRET, which
 * holds two comma-separated secrets during a rotation.
 */
export function createApp(secret: string): LingaraApp {
  // Hold per-learner state on `subject`, the same value your events carry.
  const streaks = new Map<string, number>();
  return lingaraApp({
    secret: secret.split(","),
    render: (request) => localisedCard(request.locale, streaks.get(request.subject) ?? 0),
    actions: {
      // The button's `action`; it may arrive twice, so make it safe to repeat.
      done: (request) => {
        streaks.set(request.subject, (streaks.get(request.subject) ?? 0) + 1);
        return localisedCard(request.locale, streaks.get(request.subject) ?? 0);
      },
    },
  });
}
