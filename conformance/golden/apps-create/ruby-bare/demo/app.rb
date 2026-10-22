# frozen_string_literal: true

require "lingara/apps"

# Demo: one render and one action, keyed on the learner.
module LingaraApp
  # One catalogue, checked at startup: a key missing in a locale falls back to
  # the default's text. Add a locale by adding its strings.
  MESSAGES = Lingara::Apps::Messages.new(default_locale: "en", catalogue: {
    "en" => {"heading" => "Today's five", "review" => "Review 3 words", "done" => "Done ({streak})"},
    "zh-Hans" => {"heading" => "今日五词", "review" => "复习 3 个词", "done" => "完成（{streak}）"}
  })

  # The learner's UI language is the request's `locale`, never the languages slice.
  def self.localised_card(locale, streak)
    t = MESSAGES.for(locale)
    Lingara::Apps.card
      .heading(t.t("heading"), 1)
      .term(word: "雨", reading: "yǔ", gloss: "rain", lang: "zh")
      .list([Lingara::Apps.item.text(t.t("review")), Lingara::Apps.item.term(word: "二", reading: "èr")])
      .button(t.t("done", "streak" => streak.to_s), "done")
      .build
  end

  # +secret+ is LINGARA_APP_SECRET: two, comma-separated, during a rotation.
  def self.app(secret)
    # Hold per-learner state on `subject`, the same value your events carry.
    streaks = Hash.new(0)
    app = Lingara::Apps::App.new(secret: secret.split(","))
    app.render { |request| localised_card(request.locale, streaks[request.subject]) }
    # The button's `action`; it may arrive twice, so make it safe to repeat.
    app.action("done") do |request|
      streaks[request.subject] += 1
      localised_card(request.locale, streaks[request.subject])
    end
    app
  end
end
