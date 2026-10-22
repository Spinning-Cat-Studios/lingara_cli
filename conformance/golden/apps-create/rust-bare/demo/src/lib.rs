//! Demo: one render and one action, keyed on the learner.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use lingara_apps::{App, AppActionRequest, AppRenderRequest, BoxError, Card, Messages, MessagesError, Term, card, item};

/// Per-learner state, keyed on `subject`, the same value your events carry.
type Streaks = Arc<Mutex<HashMap<String, u32>>>;

// One catalogue, checked at startup: a key missing in a locale falls back to
// the default's text. Add a locale by adding its strings.
pub fn messages() -> Result<Messages, MessagesError> {
    Messages::new(
        "en",
        [
            ("en", vec![("heading", "Today's five"), ("review", "Review 3 words"), ("done", "Done ({streak})")]),
            ("zh-Hans", vec![("heading", "今日五词"), ("review", "复习 3 个词"), ("done", "完成（{streak}）")]),
        ],
    )
}

// The learner's UI language is the request's `locale`, never the languages slice.
pub fn localised_card(messages: &Messages, locale: &str, streak: u32) -> Result<Card, BoxError> {
    let t = messages.for_locale(locale);
    let streak = streak.to_string();
    Ok(card()
        .heading(t.t("heading").as_str(), 1)
        .term(Term::new("雨").reading("yǔ").gloss("rain").lang("zh"))
        .list([item::text(t.t("review").as_str()), item::term(Term::new("二").reading("èr"))])
        .button(t.t_with("done", &[("streak", streak.as_str())]).as_str(), "done")
        .build()?)
}

/// The app. `secrets` is LINGARA_APP_SECRET split on commas: two during a rotation.
pub fn app(secrets: Vec<String>) -> Result<App, BoxError> {
    let messages = Arc::new(messages()?);
    let streaks = Streaks::default();
    let (render_messages, render_streaks) = (Arc::clone(&messages), Arc::clone(&streaks));
    let app = App::new(secrets, move |request: AppRenderRequest| {
        let (messages, streaks) = (Arc::clone(&render_messages), Arc::clone(&render_streaks));
        async move {
            let streak = streaks.lock().map_err(|_| "poisoned")?.get(&request.subject).copied().unwrap_or(0);
            localised_card(&messages, &request.locale, streak)
        }
    })?
    // The button's `action`; it may arrive twice, so make it safe to repeat.
    .action("done", move |request: AppActionRequest| {
        let (messages, streaks) = (Arc::clone(&messages), Arc::clone(&streaks));
        async move {
            let streak = {
                let mut seen = streaks.lock().map_err(|_| "poisoned")?;
                let streak = seen.entry(request.subject.clone()).or_default();
                *streak += 1;
                *streak
            };
            localised_card(&messages, &request.locale, streak)
        }
    });
    Ok(app)
}
