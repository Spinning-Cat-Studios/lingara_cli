// Every object `delete` (ADR 16.10.26ad D5): the ritual over one `<word>.get`
// for the prompt, then the destructive request with the canonical email.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.strOrNull

/** The kind's `consequence_*` line, from the object's read. */
typealias Consequence = (JsonNode) -> String

/** Every object `delete`'s arguments: the id, an optional version, the ritual's flags. */
data class DeleteArgs(val id: String, val version: String?, val confirm: ConfirmArgs)

object Deletes {
    /** `{client}` is the read's `parent_id`, the client's platform object id: the one `lingara client delete <id>` takes. */
    fun parent(obj: JsonNode): String = obj.strOrNull("parent_id") ?: "-"

    fun prompt(k: Kind, obj: JsonNode, id: String, version: String?, consequence: Consequence) = Prompt(
        kind = k.label,
        name = obj.strOrNull("name") ?: id,
        id = id,
        consequence = if (version != null) Assets.message("consequence_version", "semver" to version) else consequence(obj),
        pinnedBy = obj.path("pinned_by").takeIf { it.isIntegralNumber }?.asLong() ?: 0,
    )

    fun deleteObject(ctx: Ctx, k: Kind, args: DeleteArgs, consequence: Consequence) {
        val email = Confirm.destructive(ctx, args.confirm) { c -> prompt(k, Objects.get(c, k, args.id), args.id, args.version, consequence) }
        Objects.delete(ctx, k, args.id, args.version, email)
        ctx.out.nothing()
    }

    /** The consequence line that names the object's client: apps, embeds, inbound webhooks. */
    fun naming(id: String): Consequence = { obj -> Assets.message(id, "client" to parent(obj)) }
}
