package org.mattshoe.mtg.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Whose collection. There is no default, anywhere, on purpose. */
enum class Owner(val slug: String) {
    MATT("matt"),
    KAYLA("kayla");

    val label: String get() = slug.replaceFirstChar(Char::uppercaseChar)
}

/** Cards in or cards out. The first question the wizard asks. */
enum class Direction(val slug: String, val verb: String) {
    ADD("add", "Add"),
    REMOVE("remove", "Remove");

    val path: String get() = "/cards/$slug"
    val question: String get() = if (this == ADD) "What are you adding?" else "What are you removing?"
}

/**
 * One printing moving, as the server described it.
 *
 * The wire form is a positional array — `[name, set, collnum, finish,
 * before, after]` — which is cheap to send and awful to read, so it is
 * turned into something named exactly once, here.
 */
@Serializable(with = ChangeSerializer::class)
data class Change(
    val name: String,
    val set: String,
    val collectorNumber: String,
    val finish: String,
    val before: Int,
    val after: Int,
) {
    val isIncrease: Boolean get() = after > before
}

@Serializable
data class Applied(
    val applied: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false,
    val resolved: Int = 0,
    val failed: Int = 0,
    val changes: List<Change> = emptyList(),
    val errors: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class Unlocked(
    val token: String = "",
    @SerialName("expires_at") val expiresAt: Long? = null,
)

/** What the server says when it refuses. */
class ApiFailure(message: String) : Exception(message)

internal object ChangeSerializer :
    kotlinx.serialization.KSerializer<Change> {

    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("Change")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Change {
        val input = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw kotlinx.serialization.SerializationException("Change is JSON only")
        val row = input.decodeJsonElement() as? JsonArray
            ?: throw kotlinx.serialization.SerializationException("expected an array")
        fun at(i: Int): JsonElement? = row.getOrNull(i)
        return Change(
            name = at(0)?.jsonPrimitive?.content.orEmpty(),
            set = at(1)?.jsonPrimitive?.content.orEmpty(),
            collectorNumber = at(2)?.jsonPrimitive?.content.orEmpty(),
            finish = at(3)?.jsonPrimitive?.content.orEmpty(),
            before = at(4)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            after = at(5)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Change) {
        throw kotlinx.serialization.SerializationException("changes are only ever read")
    }
}
