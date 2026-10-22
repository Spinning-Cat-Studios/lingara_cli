# {{display_name}}

{{description}}

A Lingara app: its objects are `lingara/connection.js` (open it in the
builder's Code tab too), its card is `src/main/java/app/Cards.java`.

```sh
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # push lingara/connection.js to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `gradle --quiet run` with `LINGARA_APP_SECRET` set for this session only.
Nothing secret is written to disk. When the app has a real address, set
`renderUrl` in `lingara/connection.js` and cut a version with
`lingara apps update --version 1.0.0`.

`gradle test` signs a fake render offline and checks a card comes back. The
build needs Gradle 8 and JDK 17 or newer; `gradle wrapper` pins one in the project.
