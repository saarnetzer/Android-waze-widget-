package com.wazetoggle

import android.content.Context

enum class Mode { CAR, MOTO;
    fun other() = if (this == CAR) MOTO else CAR
}

/**
 * The Waze screens we walk through, in order. Each step has a list of on-screen labels
 * (Hebrew + English) that identify the button to tap. All lists are user-editable from
 * the app's main screen, in case Waze renames something.
 */
enum class Step(
    val key: String,
    val title: String,
    val defaults: List<String>,
    /**
     * Waze exposes many controls to accessibility only as internal IDs in their content description
     * (e.g. "HAMBURGER_BUTTON", "START_STATE_SEARCH_FIELD"), often in English even when the UI is Hebrew.
     * A node whose ID-like description / view id contains one of these tokens also matches.
     */
    val idHints: List<String> = emptyList(),
) {
    MAP("map", "0. סימן שהמפה הראשית פתוחה",
        listOf("START_STATE_SEARCH_FIELD", "WAZE_BOTTOM_SHEET_HANDLE", "לאן?", "Where to?"),
        listOf("START_STATE_SEARCH_FIELD", "START_STATE_")),
    MENU("menu", "1. כפתור התפריט ☰",
        listOf("HAMBURGER_BUTTON", "Menu", "Main menu", "Open menu", "My Waze",
            "תפריט", "תפריט ראשי", "פתיחת תפריט", "Waze שלי"),
        listOf("HAMBURGER", "MAIN_MENU")),
    SETTINGS("settings", "2. הגדרות",
        listOf("הגדרות", "Settings", "Waze settings", "הגדרות Waze"),
        listOf("SETTINGS")),
    VEHICLE_DETAILS("vehicle_details", "3. פרטי הרכב",
        listOf("פרטי הרכב", "Vehicle details", "Vehicle info", "Your vehicle", "My vehicle",
            "פרטי רכב", "פרטי כלי הרכב", "הרכב שלי"),
        listOf("VEHICLE_DETAILS", "VEHICLE_INFO", "MY_VEHICLE")),
    VEHICLE_TYPE("vehicle_type", "4. סוג רכב",
        listOf("סוג רכב", "Vehicle type", "Type of vehicle", "סוג הרכב", "סוג כלי רכב"),
        listOf("VEHICLE_TYPE")),
    TARGET_CAR("target_car", "5א. אפשרות 'רכב פרטי'",
        listOf("רכב פרטי", "Private car", "Private", "Private vehicle", "Car", "פרטי"),
        listOf("PRIVATE")),
    TARGET_MOTO("target_moto", "5ב. אפשרות 'אופנוע'",
        listOf("אופנוע", "Motorcycle", "Motorbike", "Motor bike"),
        listOf("MOTORCYCLE", "MOTORBIKE"));
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("waze_toggle", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.CAR.name)!!) }.getOrDefault(Mode.CAR)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var lastLog: String
        get() = sp.getString("last_log", "") ?: ""
        set(v) = sp.edit().putString("last_log", v).apply()

    /** User additions first (they win), then the built-in defaults, so app updates still add new defaults. */
    fun labels(step: Step): List<String> = (custom(step) + step.defaults).distinct()

    private fun custom(step: Step): List<String> =
        sp.getString("labels_${step.key}", null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun labelsRaw(step: Step): String = labels(step).joinToString(", ")

    fun setLabels(step: Step, raw: String) {
        if (raw.isBlank()) sp.edit().remove("labels_${step.key}").apply()
        else sp.edit().putString("labels_${step.key}", raw).apply()
    }

    fun resetLabels() {
        val e = sp.edit()
        Step.entries.forEach { e.remove("labels_${it.key}") }
        e.apply()
    }
}
