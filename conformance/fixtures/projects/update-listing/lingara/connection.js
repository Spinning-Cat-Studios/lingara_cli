// lingara connection v1 (schema 2)

const n1 = lingara.client({ name: "Dive Log", scopes: ["lesson_plans:read"], redirectUris: [] })

const n2 = lingara.secret({ client: n1 })

const hooks = lingara.webhook({
  client: n1,
  url: 'https://dive.example/hooks',
  events: ["lesson_plan.ready"],
})

const luba = lingara.app({
  client: n1,
  manifest: {
    defaultLocale: "ja",
    name: "ダイブログ",
    description: { ja: "潜水の記録。", en: "Your dives, logged.", },
    renderUrl: "https://dive.example/lingara/render",
    slots: ["home.side", "plans.empty_detail"],
    context: ["languages", "plan_summary"],
    scopes: ["lesson_plans:read"],
    tutorNote: true,
    longDescription: "一段落目。\n\n二段落目。",
    developer: { name: "Luba", url: "https://dive.example", supportUrl: "https://dive.example/help" },
    images: [
      { sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", role: "hero", alt: "地図" },
      { sha256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", role: "screenshot", alt: { ja: "記録", en: "Log" } },
    ],
  },
})
