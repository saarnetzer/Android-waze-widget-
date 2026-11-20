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
enum class Step(val key: String, val title: String, val defaults: List<String>) {
    MENU("menu", "1. כפתור התפריט (My Waze)",
        listOf("My Waze", "Waze שלי", "הוויז שלי", "Menu", "תפריט", "Open menu", "פתיחת תפריט")),
    SETTINGS("settings", "2. הגדרות",
        listOf("Settings", "הגדרות")),
    NAVIGATION("navigation", "3. ניווט",
        listOf("Navigation", "ניווט")),
    VEHICLE_DETAILS("vehicle_details", "4. פרטי הרכב",
        listOf("Vehicle details", "Vehicle info", "פרטי הרכב", "פרטי רכב", "פרטי כלי הרכב")),
    VEHICLE_TYPE("vehicle_type", "5. סוג הרכב",
        listOf("Vehicle type", "סוג רכב", "סוג הרכב", "סוג כלי הרכב")),
    TARGET_CAR("target_car", "6א. אפשרות 'רכב פרטי'",
        listOf("Private", "Car", "Private car", "Private vehicle", "פרטי", "רכב פרטי")),
    TARGET_MOTO("target_moto", "6ב. אפשרות 'אופנוע'",
        listOf("Motorcycle", "Motorbike", "אופנוע"));
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("waze_toggle", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.CAR.name)!!) }.getOrDefault(Mode.CAR)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var lastLog: String
        get() = sp.getString("last_log", "") ?: ""
        set(v) = sp.edit().putString("last_log", v).apply()

    fun labels(step: Step): List<String> {
        val raw = sp.getString("labels_${step.key}", null) ?: return step.defaults
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

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
