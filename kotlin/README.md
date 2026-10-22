# lingara, the Lingara developer CLI (Kotlin)

`lingara` creates and manages the things a developer builds on Lingara: OAuth
clients, apps, embeds, inbound and outbound webhooks, and canvases. This is
the Kotlin kit, `com.getlingara:lingara-cli-kotlin`, on Clikt; it runs on any
JDK 17 or newer.

```sh
jbang app install --name lingara com.getlingara:lingara-cli-kotlin:<version>
# or, with no dependency resolution at all:
java -jar lingara-cli-kotlin-<version>-all.jar --version
```

## Sign in

```sh
lingara login            # the browser, through a loopback redirect (PKCE)
lingara login --device   # a code to enter elsewhere, for SSH sessions
lingara whoami
lingara logout
```

Credentials go to the OS keychain, or to `~/.config/lingara/credentials.json`
(mode `0600`) where there is none. `LINGARA_KEYRING=file` or `=keychain`
chooses; `--profile <name>` (or `LINGARA_PROFILE`) keeps several apart.

## CI

Make a CI client once, then give the job its id and secret:

```sh
lingara client create --name 'Release pipeline' --ci
export LINGARA_PLATFORM_CLIENT_ID=… LINGARA_PLATFORM_CLIENT_SECRET=…
```

Every command then signs in with those, and stores nothing.

## Deleting asks twice

A delete asks `y/n`, then asks you to type your developer email. Away from a
terminal it refuses unless both flags are on the command line:

```sh
lingara client delete <id> -y --confirm you@example.com
```

## Building it

The Java and Kotlin kits share one Gradle build at the repository root:

```sh
./gradlew :kotlin:shadowJar   # kotlin/build/libs/lingara-cli-kotlin-<version>-all.jar
./gradlew :kotlin:check       # the unit tests
```

`src/main/resources/lingara/` holds committed copies of the shared spec files
and app templates (`make sync-kit-assets`); edit their sources, never these.
