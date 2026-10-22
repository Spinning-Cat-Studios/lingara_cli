package app

import com.getlingara.apps.kotlin.manifest
import com.getlingara.apps.kotlin.model.AppSlotName
import com.getlingara.apps.kotlin.model.ContextSliceKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Each wire name as the kit's enum, through the enum's own serializer. */
private fun <T> wire(
    values: List<String>,
    element: kotlinx.serialization.KSerializer<T>,
): List<T> = Json.decodeFromString(ListSerializer(element), Json.encodeToString(ListSerializer(String.serializer()), values))

// The manifest as code. `lingara apps update` runs `gradle --quiet manifest` and uploads what
// this prints, so print nothing else.
fun main() {
    val slots = wire(listOf("home.side"), AppSlotName.serializer())
    val context = wire(listOf(), ContextSliceKind.serializer())
    val json =
        manifest {
            defaultLocale = "en"
            name(mapOf("en" to "Demo", "zh-Hans" to "Demo"))
            description(mapOf("en" to "A Lingara app.", "zh-Hans" to "A Lingara app."))
            // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
            renderUrl = "https://dev.invalid/"
            slots(*slots.toTypedArray())
            context(*context.toTypedArray())
        }.toJson() // refuses what the upload would, naming the rule
    println(json)
}
