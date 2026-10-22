# Demo

A Lingara app.

A Lingara app with both halves in code: `src/manifest.ts` prints the manifest,
and `src/server.ts` verifies and answers on the kit's own primitives.

```sh
npm install            # once
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `npm run --silent manifest` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `npm start` with `LINGARA_APP_SECRET` set for this session only. Nothing
secret is written to disk. When the app has a real address, set it in
`src/manifest.ts` and cut a version with `lingara apps update --version 1.0.0`.

`npm test` signs a fake render offline and checks a card comes back.
