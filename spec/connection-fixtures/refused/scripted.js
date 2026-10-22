// lingara connection v1 (schema 2)

const n1 = lingara.client({ name: "M", scopes: [] })

const hooks = lingara.webhook({ client: n1, url: "https://m.example/hooks", events: [] }, function hooks(input) {
  return lingara.webhook(input)
})
