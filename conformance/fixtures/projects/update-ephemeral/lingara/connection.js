// lingara connection v1 (schema 2)

const client = lingara.client({ name: "Luba", scopes: [] })

const app = lingara.app({
  client: client,
  manifest: {
    name: { en: "Luba", ja: "ルバ" },
    description: { en: "Your dives, logged.", ja: "潜水の記録。" },
    renderUrl: "https://shy-owl-1.trycloudflare.com/",
    slots: ["home.side"],
  },
})
