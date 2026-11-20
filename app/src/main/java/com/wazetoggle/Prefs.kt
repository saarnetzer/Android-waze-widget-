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
        listOf("My Waze", "Waze שלי", "ה-Waze שלי", "הWaze שלי", "וויז שלי", "הוויז שלי", "ויז שלי",
            "Menu", "Main menu", "Open menu", "תפריט", "תפריט ראשי", "פתיחת תפריט", "פתח תפריט")),
    SETTINGS("settings", "2. הגדרות",
        listOf("Settings", "הגדרות", "Waze settings", "הגדרות Waze")),
    NAVIGATION("navigation", "3. ניווט",
        listOf("Navigation", "Navigation settings", "ניווט", "הגדרות ניווט")),
    VEHICLE_DETAILS("vehicle_details", "4. פרטי הרכב",
        listOf("Vehicle details", "Vehicle info", "Your vehicle", "My vehicle",
            "פרטי הרכב", "פרטי רכב", "פרטי כלי הרכב", "הרכב שלי", "הרכב שלך")),
    VEHICLE_TYPE("vehicle_type", "5. סוג הרכב",
        listOf("Vehicle type", "Type of vehicle", "סוג רכב", "סוג הרכב", "סוג כלי רכב", "סוג כלי הרכב")),
    TARGET_CAR("target_car", "6א. אפשרות 'רכב פרטי'",
        listOf("Private", "Private car", "Private vehicle", "Car",
            "פרטי", "רכב פרטי", "מכונית פרטית", "מכונית")),
    TARGET_MOTO("target_moto", "6ב. אפשרות 'אופנוע'",
        listOf("Motorcycle", "Motorbike", "Motor bike", "אופנוע", "קטנוע", "דו-גלגלי"));
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
