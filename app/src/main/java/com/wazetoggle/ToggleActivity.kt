package com.wazetoggle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** Invisible activity started by the widget. Activities may start other apps; receivers may not. */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startToggle(this)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun startToggle(activity: Activity) {
            val service = WazeAccessibilityService.instance
            if (service == null) {
                Toast.makeText(activity, R.string.enable_service_toast, Toast.LENGTH_LONG).show()
                activity.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val launch = activity.packageManager.getLaunchIntentForPackage(WazeAccessibilityService.WAZE)
            if (launch == null) {
                Toast.makeText(activity, R.string.waze_missing, Toast.LENGTH_LONG).show()
                return
            }
            val target = Prefs(activity).mode.other()
            service.begin(target)
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
