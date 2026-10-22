// lingara connection v1 (schema 2)

const n1 = lingara.client({ name: "M", scopes: [] })

const a = lingara.app({ client: n1, manifest: { name: "A", description: "A.", renderUrl: "https://a.example/" } })
