package com.wazetoggle

object Labels {
    private val bidi = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val niqqud = Regex("[\\u0591-\\u05BD\\u05BF-\\u05C7]")
    private val punct = Regex("[-\\u05BE'\"׳״:.,!?()]")
    private val spaces = Regex("\\s+")

    /**
     * Makes Hebrew/English labels comparable: drops bidi marks, niqqud, punctuation, hyphens/maqaf,
     * spaces and case, and the Hebrew definite article "ה" at the start of each word
     * (so "סוג הרכב" == "סוג רכב", "ה-Waze שלי" == "Waze שלי", "Vehicle Type" == "vehicle type").
     */
    fun normalize(s: CharSequence?): String {
        if (s == null) return ""
        return s.toString()
            .replace(bidi, "")
            .replace(niqqud, "")
            .lowercase()
            .replace(punct, " ")
            .split(spaces)
            .filter { it.isNotEmpty() && it != "ה" }
            .joinToString("") { w -> if (w.length > 2 && w.startsWith("ה")) w.substring(1) else w }
    }
}
