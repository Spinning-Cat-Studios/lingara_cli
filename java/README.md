# lingara, the Lingara developer CLI (Java)

`lingara` creates and manages the things a developer builds on Lingara: OAuth
clients, apps, embeds, inbound and outbound webhooks, and canvases. This is
the Java kit, `com.getlingara:lingara-cli-java` on Maven Central; it needs a
JDK 17 or newer.

```sh
jbang app install --name lingara com.getlingara:lingara-cli-java:<version>
# or, with no dependency resolution at all:
java -jar lingara-cli-java-<version>-all.jar --version
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

Neither flag comes from the environment or configuration.

## Output

Tables for people; `--json` for scripts, which prints the platform's JSON
unchanged (a list as one array of every page). Exit codes are listed in the
reference and are the same in every language of this CLI.

## Working on this kit

From the repository root: `make build-java` builds the shaded jar,
`make test-java` runs Checkstyle at the repository's budgets and the unit
tests, and `make conformance-java` runs every conformance case against the
jar. The command tree is picocli's, in `cli/`; `lingara __tree` reads it back
from picocli's own model for `make check-tree-java`.
