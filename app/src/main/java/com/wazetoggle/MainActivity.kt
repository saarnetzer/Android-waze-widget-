package com.wazetoggle

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup / status screen. Built in code to keep the project small. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var logView: TextView
    private val labelFields = mutableMapOf<Step, EditText>()
    private val carId = View.generateViewId()
    private val motoId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        col.addView(header("איך זה עובד"))
        col.addView(text(
            "1. הפעל את שירות הנגישות (כפתור למטה).\n" +
            "2. הוסף למסך הבית את הווידגט (כפתור למטה, או: לחיצה ארוכה על המסך ← ווידג'טים). " +
            "שים לב: אייקון האפליקציה עצמו לא מתחלף – רק הווידגט.\n" +
            "3. לחיצה על הווידגט פותחת את Waze ומחליפה את סוג הרכב אוטומטית (כ-3 שניות)."
        ))

        status = text("")
        status.setTypeface(null, Typeface.BOLD)
        col.addView(status)
        col.addView(button("פתח הגדרות נגישות") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(button("➕ הוסף את הווידגט למסך הבית") { pinWidget() })

        col.addView(header("מצב נוכחי ב-Waze"))
        col.addView(text("אם הווידגט לא תואם למה שמוגדר בפועל ב-Waze, תקן כאן:"))
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { id = carId; text = "🚗 רכב" })
            addView(RadioButton(context).apply { id = motoId; text = "🏍️ אופנוע" })
            setOnCheckedChangeListener { _, id ->
                prefs.mode = if (id == motoId) Mode.MOTO else Mode.CAR
                VehicleWidgetProvider.updateAll(this@MainActivity)
            }
        }
        col.addView(modeGroup)
        col.addView(button("נסה עכשיו (כמו לחיצה על הווידגט)") { ToggleActivity.startToggle(this) })

        col.addView(header("יומן הריצה האחרונה"))
        logView = text("").apply { textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }
        col.addView(logView)
        col.addView(text("היומן נשמר רק בטלפון, וייתכן שיופיעו בו טקסטים ממסך Waze (למשל יעדים אחרונים).").apply { textSize = 12f })
        col.addView(button("🗑️ נקה יומן") {
            prefs.lastLog = ""
            logView.text = "(היומן נוקה)"
        })

        col.addView(header("טקסטים לזיהוי (מתקדם)"))
        col.addView(text(
            "השירות מחפש את הטקסטים האלה על המסך של Waze, לפי הסדר. אם Waze שינה ניסוח, " +
            "העתק מהיומן למעלה את הטקסט המדויק והוסף אותו כאן (מופרד בפסיקים)."
        ))
        for (step in Step.entries) {
            col.addView(text(step.title).apply { setTypeface(null, Typeface.BOLD) })
            val et = EditText(this).apply { setText(prefs.labelsRaw(step)); textSize = 14f }
            labelFields[step] = et
            col.addView(et)
        }
        col.addView(button("שמור טקסטים") {
            labelFields.forEach { (s, et) -> prefs.setLabels(s, et.text.toString()) }
            Toast.makeText(this, "נשמר", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("שחזר ברירת מחדל") {
            prefs.resetLabels()
            labelFields.forEach { (s, et) -> et.setText(prefs.labelsRaw(s)) }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = WazeAccessibilityService.instance != null
        status.text = if (on) "✅ שירות הנגישות פעיל" else "❌ שירות הנגישות כבוי – יש להפעיל אותו"
        modeGroup.check(if (prefs.mode == Mode.MOTO) motoId else carId)
        logView.text = prefs.lastLog.ifBlank { "(עדיין לא בוצעה החלפה)" }
        VehicleWidgetProvider.updateAll(this)
    }

    private fun pinWidget() {
        val mgr = android.appwidget.AppWidgetManager.getInstance(this)
        val provider = android.content.ComponentName(this, VehicleWidgetProvider::class.java)
        if (!mgr.isRequestPinAppWidgetSupported || !mgr.requestPinAppWidget(provider, null, null)) {
            Toast.makeText(this, "המסך הזה לא תומך בהוספה אוטומטית – לחיצה ארוכה על מסך הבית ← ווידג'טים", Toast.LENGTH_LONG).show()
        }
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setTypeface(null, Typeface.BOLD)
        setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun text(s: String) = TextView(this).apply { text = s; textSize = 15f; setPadding(0, 4, 0, 4) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setOnClickListener { onClick() }
    }
}
