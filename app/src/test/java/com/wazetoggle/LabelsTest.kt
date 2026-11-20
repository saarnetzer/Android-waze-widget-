package com.wazetoggle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LabelsTest {
    private fun same(a: String, b: String) = assertEquals(Labels.normalize(a), Labels.normalize(b))

    @Test fun hebrewDefiniteArticle() {
        same("סוג הרכב", "סוג רכב")
        same("פרטי הרכב", "פרטי רכב")
        same("ה-Waze שלי", "Waze שלי")
        same("הוויז שלי", "וויז שלי")
    }

    @Test fun englishCaseAndSpacing() {
        same("Vehicle Type", "vehicle type")
        same("  My Waze ", "My Waze")
        same("Navigation:", "Navigation")
    }

    @Test fun bidiMarksAndMaqaf() {
        same("‏הגדרות‏", "הגדרות")
        same("דו־גלגלי", "דו-גלגלי")
    }

    @Test fun differentWordsStayDifferent() {
        assertNotEquals(Labels.normalize("פרטי"), Labels.normalize("פרטי הרכב"))
        assertNotEquals(Labels.normalize("Private"), Labels.normalize("Motorcycle"))
        assertNotEquals(Labels.normalize("אופנוע"), Labels.normalize("פרטי"))
    }
}
