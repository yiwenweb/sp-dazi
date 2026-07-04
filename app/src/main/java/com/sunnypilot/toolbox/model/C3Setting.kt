package com.sunnypilot.toolbox.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class C3SettingMeta(
    val key: String,
    val type: String,
    val title: String,
    val desc: String,
    val value: JsonElement? = null,
    val error: String? = null,
    val choices: List<String>? = null
)

@Serializable
data class C3SettingValue(
    val key: String,
    val value: JsonElement? = null
)

@Serializable
data class C3SettingResult(
    val success: Boolean? = null,
    val key: String? = null,
    val value: JsonElement? = null,
    val error: String? = null
)

fun C3SettingMeta.valueAsBoolean(): Boolean {
    return value?.toString()?.trim() == "true" || value?.toString()?.trim() == "1"
}

fun C3SettingMeta.valueAsInt(): Int {
    return value?.toString()?.toIntOrNull() ?: 0
}

fun C3SettingMeta.valueAsBooleanIndex(): Int = if (valueAsBoolean()) 1 else 0

fun C3SettingMeta.valueAsChoiceIndex(): Int = valueAsInt().coerceIn(0, (choices?.size ?: 1) - 1)

fun List<C3SettingMeta>.asJson(): String =
    Json.encodeToString(ListSerializer(C3SettingMeta.serializer()), this)
