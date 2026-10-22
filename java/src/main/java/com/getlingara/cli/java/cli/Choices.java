package com.getlingara.cli.java.cli;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * The schema's enumerated values, each a Java enum whose wire spelling is its own (some, such as
 * {@code home.side}, are not Java identifiers). picocli reads an option's type from its field, so
 * {@code __tree} reports these as {@code enum} with the values below, from the framework's own
 * model (ADR 16.10.26af D2: picocli needs no kit-local table).
 */
public final class Choices {
    private Choices() {}

    /** An enum constant's spelling on the command line. */
    public interface Wired {
        String wire();
    }

    /** The values picocli lists as an option's completion candidates, and {@code __tree} as its values. */
    public abstract static class Values<E extends Enum<E> & Wired> implements Iterable<String> {
        private final Class<E> type;

        protected Values(Class<E> type) {
            this.type = type;
        }

        @Override
        public Iterator<String> iterator() {
            List<String> out = new ArrayList<>();
            for (E e : type.getEnumConstants()) {
                out.add(e.wire());
            }
            return out.iterator();
        }
    }

    /** Exact wire spellings only: anything else is a parse error, so {@code usage}. */
    public abstract static class Converter<E extends Enum<E> & Wired> implements ITypeConverter<E> {
        private final Class<E> type;

        protected Converter(Class<E> type) {
            this.type = type;
        }

        @Override
        public E convert(String value) {
            for (E e : type.getEnumConstants()) {
                if (e.wire().equals(value)) {
                    return e;
                }
            }
            throw new TypeConversionException("'" + value + "' is not one of " + String.join(", ", new Values<E>(type) {}));
        }
    }

    /** {@code --port}: a whole number from 1 to 65535. */
    public static final class Port implements ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // Reported below, as any value out of range is.
            }
            throw new TypeConversionException("'" + value + "' is not a port from 1 to 65535");
        }
    }

    /** An app kit's language (the app SDK's {@code languages.toml} ids). */
    public enum Lang implements Wired {
        TYPESCRIPT, RUST, GO, JAVA, KOTLIN, RUBY, PHP;

        @Override
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static final class Of extends Values<Lang> {
            public Of() {
                super(Lang.class);
            }
        }

        public static final class Conv extends Converter<Lang> {
            public Conv() {
                super(Lang.class);
            }
        }
    }

    /** Where an app's card may appear (the app view's {@code AppSlotName}). */
    public enum Slot implements Wired {
        HOME_SIDE("home.side"), PLANS_EMPTY_DETAIL("plans.empty_detail");

        private final String wire;

        Slot(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        public static final class Of extends Values<Slot> {
            public Of() {
                super(Slot.class);
            }
        }

        public static final class Conv extends Converter<Slot> {
            public Conv() {
                super(Slot.class);
            }
        }
    }

    /** Whether an OAuth client keeps a secret. */
    public enum ClientType implements Wired {
        CONFIDENTIAL, PUBLIC;

        @Override
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static final class Of extends Values<ClientType> {
            public Of() {
                super(ClientType.class);
            }
        }

        public static final class Conv extends Converter<ClientType> {
            public Conv() {
                super(ClientType.class);
            }
        }
    }

    /** How an OAuth client's usage is billed. */
    public enum BillingMode implements Wired {
        ALLOWANCE, METERED;

        @Override
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static final class Of extends Values<BillingMode> {
            public Of() {
                super(BillingMode.class);
            }
        }

        public static final class Conv extends Converter<BillingMode> {
            public Conv() {
                super(BillingMode.class);
            }
        }
    }

    /** The tunnel {@code apps dev} opens; {@code none} needs {@code --render-url}. */
    public enum TunnelKind implements Wired {
        CLOUDFLARED, NONE;

        @Override
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static final class Of extends Values<TunnelKind> {
            public Of() {
                super(TunnelKind.class);
            }
        }

        public static final class Conv extends Converter<TunnelKind> {
            public Conv() {
                super(TunnelKind.class);
            }
        }
    }
}
