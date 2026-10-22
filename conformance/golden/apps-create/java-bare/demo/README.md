# Demo

A Lingara app.

A Lingara app with both halves in Java: `ManifestMain` prints the manifest
from the kit's builder, and `Main` serves the kit's JDK handler over
`LingaraApp.handle`, the kit's own primitive.

```sh
lingara login          # once
lingara apps dev       # serve it from this machine to your own install, until Ctrl+C
lingara apps update    # run `gradle --quiet manifest` and push what it prints to your dev version
```

`lingara apps dev` opens a tunnel, points your private dev version at it, and
runs `gradle --quiet run` with `LINGARA_APP_SECRET` set for this session only.
Nothing secret is written to disk. When the app has a real address, set it in
`ManifestMain.java` and cut a version with `lingara apps update --version 1.0.0`.

`gradle test` signs a fake render offline and checks a card comes back. The
build needs Gradle 8 and JDK 17 or newer; `gradle wrapper` pins one in the project.
