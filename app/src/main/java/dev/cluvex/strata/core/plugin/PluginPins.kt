package dev.cluvex.strata.core.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PluginTrust {
    NEW,

    TRUSTED,

    CHANGED,
}

class PluginPins private constructor(private val pins: Map<String, String>) {

    fun pinned(packageName: String): String? = pins[packageName]

    fun trust(plugin: PluginInfo): PluginTrust {
        val pin = pins[plugin.packageName] ?: return PluginTrust.NEW
        return if (pin == plugin.signer || pin in plugin.pastSigners) PluginTrust.TRUSTED else PluginTrust.CHANGED
    }

    fun with(packageName: String, signer: String) = PluginPins(pins + (packageName to signer))

    fun without(packageName: String) = PluginPins(pins - packageName)

    val packages: Set<String> get() = pins.keys

    fun encode(): String = JsonObject(pins.mapValues { JsonPrimitive(it.value) }).toString()

    override fun equals(other: Any?) = other is PluginPins && other.pins == pins
    override fun hashCode() = pins.hashCode()

    companion object {
        val EMPTY = PluginPins(emptyMap())

        fun decode(json: String?): PluginPins {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                PluginPins(Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.content })
            }.getOrDefault(EMPTY)
        }
    }
}
