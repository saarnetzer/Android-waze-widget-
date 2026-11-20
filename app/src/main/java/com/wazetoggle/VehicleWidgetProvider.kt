package com.wazetoggle

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class VehicleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        /** Redraws every widget instance. [busy] shows a "switching…" state. */
        fun updateAll(context: Context, busy: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VehicleWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val mode = Prefs(context).mode
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_icon, if (busy) "⏳" else if (mode == Mode.CAR) "🚗" else "🏍️")
            views.setTextViewText(
                R.id.widget_label,
                context.getString(if (mode == Mode.CAR) R.string.mode_car else R.string.mode_moto)
            )
            views.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (mode == Mode.CAR) R.drawable.widget_bg else R.drawable.widget_bg_moto
            )

            val tap = Intent(context, ToggleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val pi = PendingIntent.getActivity(
                context, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(ids, views)
        }
    }
}
