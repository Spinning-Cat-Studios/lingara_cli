// lingara connection v1 (schema 2)

const client = lingara.client({ name: "Luba", scopes: [] })

const app = lingara.app({
  client: client,
  manifest: {
    name: "Luba",
    description: "Your dives, logged.",
    renderUrl: "https://dive.example/lingara/render",
    slots: ["home.side"],
  },
})
