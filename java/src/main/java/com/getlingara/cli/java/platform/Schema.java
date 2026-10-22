package com.getlingara.cli.java.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Assets;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.ByteArrayInputStream;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code spec/platform-toml.schema.json}'s rules over the parsed file (ADR 16.10.26af D2: Java
 * validates with networknt's json-schema-validator against the kit's own committed copy of the
 * schema). The version gate runs first, by hand, so the commonest refusal reads the same in every
 * kit; the schema then names the first rule the file breaks. Built on first use: only the {@code
 * apps} commands that read a {@code platform.toml} pay for it.
 */
public final class Schema {
    private static final Pattern APP_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private Schema() {}

    private static final class Holder {
        static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(new ByteArrayInputStream(Assets.read("/lingara/platform-toml.schema.json")));
    }

    /**
     * A valid app name: a lowercase letter, then up to 39 lowercase letters, digits or hyphens.
     */
    public static boolean appNameOk(String name) {
        return APP_NAME.matcher(name).matches();
    }

    /** Why {@code value} is not a {@code platform.toml} this kit reads; {@code null} when it is. */
    public static String check(JsonNode value) {
        JsonNode schema = value.get("schema");
        if (schema == null) {
            return "schema = 1 is missing";
        }
        if (!schema.isIntegralNumber() || schema.asLong() != 1) {
            return "schema is " + schema + "; this lingara reads 1";
        }
        Set<ValidationMessage> errors = Holder.SCHEMA.validate(value);
        return errors.isEmpty() ? null : errors.iterator().next().getMessage();
    }
}
