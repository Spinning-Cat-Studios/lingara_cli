# Install `lingara` for the JVM, in Java

The Java kit runs on any JDK 17 or newer. Its Maven Central coordinates are
`com.getlingara:lingara-cli-java:<version>`.

With [jbang](https://www.jbang.dev), straight from Central:

```sh
jbang app install --name lingara com.getlingara:lingara-cli-java:<version>
lingara --version     # lingara <version> (java)
```

`jbang com.getlingara:lingara-cli-java:<version> whoami` runs it once without
installing anything.

Or with the shaded jar, which carries every dependency and needs no
resolution: download `lingara-cli-java-<version>-all.jar` from Maven Central
and run it with `java`:

```sh
java -jar lingara-cli-java-<version>-all.jar --version
alias lingara='java -jar /path/to/lingara-cli-java-<version>-all.jar'
```

The Kotlin kit, `com.getlingara:lingara-cli-kotlin`, is the same command on
the same JVM; install either.

Then sign in:

```sh
lingara login            # opens the browser
lingara login --device   # a code to enter on another machine, for SSH sessions
```

In CI, set `LINGARA_PLATFORM_CLIENT_ID` and `LINGARA_PLATFORM_CLIENT_SECRET`
to a client made with `lingara client create --ci`; nothing is stored. A
dangerous command there needs both `-y` and `--confirm <your developer
email>` on its command line.
