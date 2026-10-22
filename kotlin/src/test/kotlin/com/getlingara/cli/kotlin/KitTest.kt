// The shared vectors every kit is held to besides AC18's: the connection
// literal fixtures (16.10.26ae AC5), the manifest warnings (AC17), the
// platform.toml refusal order (D4), and the template rules (D2, D3).
package com.getlingara.cli.kotlin

import com.getlingara.cli.kotlin.apps.ManifestRules
import com.getlingara.cli.kotlin.apps.Template
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.literal.ConnectionLiteral
import com.getlingara.cli.kotlin.literal.Refusal
import com.getlingara.cli.kotlin.project.PlatformToml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitTest {
    private fun fixtures(kind: String): List<Path> =
        Files.list(Path.of("../spec/connection-fixtures", kind)).filter { it.toString().endsWith(".js") }.sorted().toList()

    private fun expected(js: Path) = Path.of(js.toString().removeSuffix(".js") + ".json").takeIf { Files.exists(it) }?.let { Json.parse(Files.readString(it)) }

    /** 16.10.26ae AC5: every valid fixture converts to its wire manifest; every refused one is refused with its code, where it says. */
    @Test
    fun connectionLiteralFixtures() {
        for (path in fixtures("valid")) {
            val text = Files.readString(path)
            ConnectionLiteral.statements(text)
            val want = expected(path)
            if (want == null) {
                assertFailsWith<Refusal>(path.toString()) { ConnectionLiteral.appManifest(text) }
            } else {
                assertEquals(Json.write(want), Json.write(ConnectionLiteral.appManifest(text)), path.toString())
            }
        }
        for (path in fixtures("refused")) {
            val want = expected(path)!!
            when (val got = assertFailsWith<Refusal> { ConnectionLiteral.appManifest(Files.readString(path)) }) {
                is Refusal.Scripted -> assertEquals(want.path("statement").textValue(), got.handle, path.toString())
                is Refusal.At -> assertEquals(
                    Triple(want.path("code").textValue(), want.path("line").asInt(), want.path("column").asInt()),
                    Triple(got.code, got.at.line, got.at.column),
                    path.toString(),
                )
            }
        }
    }

    /** 16.10.26ae AC17: every vector in spec/manifest-warnings.json gives its locales and warnings. */
    @Test
    fun manifestWarnings() {
        for (v in Json.parse(Files.readString(Path.of("../spec/manifest-warnings.json")))!!.items("vectors")) {
            assertEquals(v.path("expect").path("locales"), Json.of(ManifestRules.locales(v.path("manifest"))), v.path("name").textValue())
            assertEquals(v.path("expect").path("warnings"), Json.of(ManifestRules.warnings(v.path("manifest"))), v.path("name").textValue())
        }
        assertTrue(ManifestRules.ephemeral("https://user@X.TryCloudflare.com:443/"))
        assertTrue(!ManifestRules.ephemeral("https://trycloudflare.com.example/"))
    }

    private fun refusal(text: String): Fail {
        val file = Files.createTempFile("platform", ".toml").also { Files.writeString(it, text) }
        return assertFailsWith<Fail> { PlatformToml.load(file, "platform.toml") }
    }

    /** 16.10.26ae D4: syntax, then a secret by its key, then the schema's rules, in file order. */
    @Test
    fun platformTomlRefusals() {
        val row = "[[apps]]\nname = \"a\"\npath = \".\"\n"
        assertEquals(Exit.PLATFORM_TOML_SCHEMA, refusal("schema = \n").exit)
        val secret = refusal("schema = 1\n$row[apps.env]\nX = \"lgr_cs_abcdefgh12345\"\nAPI_TOKEN = \"x\"\n")
        assertEquals(Exit.PLATFORM_TOML_SECRET to true, secret.exit to secret.text.contains("apps.env.X"))
        assertEquals(Exit.PLATFORM_TOML_SCHEMA, refusal("schema = 2\n").exit)
        assertTrue(refusal("schema = 1\n${row}listed = true\n").text.contains("apps[0].listed is not a key this file takes"))
        // A public client id and a key that only contains a secret word are neither secrets.
        val ok = "schema = 1\n$row[apps.env]\nLINGARA_CLIENT_ID = \"lgr_cid_abcdefgh12345\"\nTOKENIZER = \"bpe\"\n"
        val file = Files.createTempFile("platform", ".toml").also { Files.writeString(it, ok) }
        assertEquals(1, PlatformToml.load(file, "platform.toml").apps.size)
    }

    /** spec/templates.md: each bare template's array syntax, and the two renaming rules. */
    @Test
    fun templateRules() {
        val two = listOf("home.side", "plans.empty_detail")
        assertEquals("""["home.side", "plans.empty_detail"]""", Template.array("go", "default", two))
        assertEquals("""[]string{"home.side", "plans.empty_detail"}""", Template.array("go", "bare", two))
        assertEquals("listOf()", Template.array("kotlin", "bare", emptyList()))
        assertEquals(".gitignore", Template.projectPath("_gitignore"))
        assertEquals("Cargo.toml", Template.projectPath("Cargo.toml.tmpl"))
    }
}
