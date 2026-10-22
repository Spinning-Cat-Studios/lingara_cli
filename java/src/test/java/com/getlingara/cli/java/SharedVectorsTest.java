package com.getlingara.cli.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.commands.apps.ManifestRules;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.literal.ConnectionLiteral;
import com.getlingara.cli.java.literal.Refusal;
import com.getlingara.cli.java.platform.Secrets;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The other shared vectors every kit reads (16.10.26ae AC5, AC17) and the version line (16.10.26af D1). */
class SharedVectorsTest {
    private static List<Path> fixtures(String kind) throws IOException {
        try (Stream<Path> files = Files.list(Fakes.repo().resolve("spec/connection-fixtures").resolve(kind))) {
            return files.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
    }

    private static JsonNode expected(Path js) throws IOException {
        Path json = js.resolveSibling(js.getFileName().toString().replaceAll("\\.js$", ".json"));
        return Files.exists(json) ? Json.parse(Files.readString(json)) : null;
    }

    /** 16.10.26ae AC5: every valid fixture converts to its wire manifest, byte for byte as JSON. */
    @Test
    void everyValidFixtureConvertsToItsWireManifest() throws IOException {
        for (Path path : fixtures("valid")) {
            String text = Files.readString(path);
            assertNotNull(ConnectionLiteral.statementKinds(text), path.toString());
            JsonNode want = expected(path);
            if (want == null) {
                assertThrows(Refusal.class, () -> ConnectionLiteral.appManifest(text), path + " has no app");
            } else {
                assertEquals(Json.write(want), Json.write(ConnectionLiteral.appManifest(text)), path.toString());
            }
        }
    }

    /** 16.10.26ae AC5: every refused fixture is refused with its code, at its position. */
    @Test
    void everyRefusedFixtureIsRefusedWhereItSays() throws IOException {
        for (Path path : fixtures("refused")) {
            JsonNode want = expected(path);
            Refusal got = assertThrows(Refusal.class, () -> ConnectionLiteral.appManifest(Files.readString(path)), path.toString());
            assertEquals(want.path("code").asText(), got.code, path.toString());
            if (got.statement != null) {
                assertEquals(want.path("statement").asText(), got.statement, path.toString());
            } else {
                assertEquals(want.path("line").asInt(), got.at.line(), path.toString());
                assertEquals(want.path("column").asInt(), got.at.column(), path.toString());
            }
        }
        Fail fail = Refusal.at("key", new com.getlingara.cli.java.literal.Lit.Pos(3, 40)).fail("lingara/connection.js");
        assertTrue(fail.text().startsWith("lingara/connection.js:3:40: "));
        assertEquals(Exit.CONNECTION_SCRIPTED, Refusal.scripted("hooks").fail("x").exit());
    }

    /** 16.10.26ae AC17: every vector in spec/manifest-warnings.json gives its locales and warnings. */
    @Test
    void manifestWarningsFollowTheSharedVectors() throws IOException {
        JsonNode file = Json.parse(Files.readString(Fakes.repo().resolve("spec/manifest-warnings.json")));
        for (JsonNode v : file.path("vectors")) {
            String name = v.path("name").asText();
            assertEquals(v.path("expect").path("locales"), Json.array(ManifestRules.locales(v.path("manifest"))), name);
            assertEquals(v.path("expect").path("warnings"), Json.array(ManifestRules.warnings(v.path("manifest"))), name);
        }
        assertTrue(ManifestRules.ephemeral("https://dev.invalid/") && ManifestRules.ephemeral("https://user@X.TryCloudflare.com:443/"));
        assertFalse(ManifestRules.ephemeral("https://trycloudflare.com.example/"));
    }

    @Test
    void secretsAreFoundByKeyAndByValue() {
        assertTrue(Secrets.secretValue("lgr_cs_abcdefgh1"));
        assertFalse(Secrets.secretValue("lgr_cid_abcdefgh1") || Secrets.secretValue("lgr_cs_short"));
        assertTrue(Secrets.secretKey("CLIENT_SECRET") && Secrets.secretKey("api_token"));
        assertFalse(Secrets.secretKey("TOKENIZER"));
    }

    /** 16.10.26af D1: {@code lingara <VERSION> (java)} and nothing else on stdout. */
    @Test
    void versionNamesTheKit() throws IOException {
        Outcome outcome = Fakes.app(new Fakes.Script(), Fakes.terminal(false))
                .run(List.of("--version"), new PrintStream(OutputStream.nullOutputStream()));
        String version = Files.readString(Fakes.repo().resolve("VERSION")).strip();
        assertEquals("lingara " + version + " (java)\n", new String(outcome.stdout(), StandardCharsets.UTF_8));
        assertEquals(Exit.OK, outcome.exit());
        assertTrue(Assets.hasMessage("store_file_only") && Assets.hasMessage("apps_dev_ready"));
    }
}
