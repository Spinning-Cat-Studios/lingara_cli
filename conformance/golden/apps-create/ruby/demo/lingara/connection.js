// lingara connection v1 (schema 2)

const client = lingara.client({ name: "Demo", scopes: [] })

const app = lingara.app({
  client: client,
  manifest: {
    name: { en: "Demo", "zh-Hans": "Demo" },
    description: { en: "A Lingara app.", "zh-Hans": "A Lingara app." },
    renderUrl: "https://dev.invalid/",
    slots: ["home.side"],
    context: [],
    defaultLocale: "en",
  },
})
