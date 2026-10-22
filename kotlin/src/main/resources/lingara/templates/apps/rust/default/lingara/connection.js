// lingara connection v1 (schema 2)

const client = lingara.client({ name: "{{display_name}}", scopes: [] })

const app = lingara.app({
  client: client,
  manifest: {
    name: { en: "{{display_name}}", "zh-Hans": "{{display_name}}" },
    description: { en: "{{description}}", "zh-Hans": "{{description}}" },
    renderUrl: "https://dev.invalid/",
    slots: {{slots}},
    context: {{context}},
    defaultLocale: "en",
  },
})
