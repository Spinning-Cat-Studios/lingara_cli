package com.getlingara.cli.java.cli;

import java.nio.file.Path;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The flag groups several commands share, as picocli mixins. */
public final class Mixins {
    private Mixins() {}

    /**
     * The {@code confirm} flag group every dangerous command carries (16.10.26ad D5). Neither flag
     * reads the environment or configuration. {@code __tree} calls a command dangerous exactly when
     * it carries {@code --confirm}, the schema's own rule (16.10.26ab D2.4).
     */
    public static final class ConfirmFlags {
        @Option(names = {"-y", "--yes"}, description = "Answer the y/n question; needs --confirm too")
        public boolean yes;

        @Option(names = "--confirm", paramLabel = "email", description = "Your developer email, typed back; needs -y too")
        public String confirm;
    }

    /** One object's id, the first positional. */
    public static final class IdArg {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;
    }

    /** Every object {@code delete}: the id, an optional version, the ritual. */
    public static final class Deletion {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--version", paramLabel = "semver", description = "Delete only this version")
        public String version;

        @Mixin
        public ConfirmFlags confirm;
    }

    /** {@code --config}: which platform.toml, before {@code LINGARA_PLATFORM_TOML}. */
    public static final class ConfigOption {
        @Option(names = "--config", paramLabel = "path",
                description = "The platform.toml to read; default the nearest at or above this directory")
        public Path config;
    }
}
