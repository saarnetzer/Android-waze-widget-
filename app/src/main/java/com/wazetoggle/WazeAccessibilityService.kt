package com.wazetoggle

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Drives Waze's UI: My Waze → Settings → Navigation → Vehicle details → Vehicle type → Private/Motorcycle.
 *
 * It is idle until [begin] is called by the widget, and only ever looks at Waze windows.
 * The walk is a forward-only state machine: on every tick it looks for the label of the *furthest*
 * step that is visible on screen (so skipped/merged screens in future Waze versions still work),
 * taps it, and moves on. If nothing matches it scrolls, and finally gives up with a log of what it saw.
 */
class WazeAccessibilityService : AccessibilityService() {

    companion object {
        const val WAZE = "com.waze"
        private const val TAG = "WazeToggle"
        private const val TICK_MS = 300L
        private const val SETTLE_MS = 800L        // wait after a tap for the next screen
        private const val STUCK_MS = 2500L        // no match for this long → try scrolling
        private const val MAX_SCROLLS = 4
        private const val TIMEOUT_MS = 25_000L

        @Volatile
        var instance: WazeAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var running = false
    private var target = Mode.CAR
    private var step = 0                 // index into walkSteps of the next step we expect
    private var startedAt = 0L
    private var lastProgressAt = 0L
    private var scrollsThisStep = 0
    private var backsAtStart = 0
    private val log = StringBuilder()

    private val walkSteps: List<Step>
        get() = listOf(
            Step.MENU, Step.SETTINGS, Step.NAVIGATION, Step.VEHICLE_DETAILS, Step.VEHICLE_TYPE,
            if (target == Mode.CAR) Step.TARGET_CAR else Step.TARGET_MOTO
        )

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Polling (see tick) does the work; events just make us react faster.
        if (running && event?.packageName == WAZE) {
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 120)
        }
    }

    /** Called from the widget, right before Waze is brought to the foreground. */
    fun begin(target: Mode) {
        handler.removeCallbacksAndMessages(null)
        this.target = target
        running = true
        step = 0
        scrollsThisStep = 0
        backsAtStart = 0
        startedAt = SystemClock.uptimeMillis()
        lastProgressAt = startedAt
        log.setLength(0)
        note("start → ${target.name}")
        VehicleWidgetProvider.updateAll(this, busy = true)
        toast(getString(R.string.switching_to, modeName(target)))
        handler.postDelayed(tickRunnable, 700)
    }

    private val tickRunnable = Runnable { tick() }

    private fun tick() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > TIMEOUT_MS) return fail("timeout")

        val root = rootInActiveWindow
        if (root == null || root.packageName != WAZE) {
            return schedule(TICK_MS)
        }

        val steps = walkSteps
        val lastIdx = steps.lastIndex
        // The final option (e.g. "Private") can also appear as a subtitle on earlier screens,
        // so we only accept it once we've opened the vehicle-type list – or as a last resort.
        val stuck = now - lastProgressAt > STUCK_MS && scrollsThisStep >= MAX_SCROLLS
        for (i in lastIdx downTo step) {
            if (i == lastIdx && step < lastIdx && !stuck) continue
            val node = findByLabels(root, prefs.labels(steps[i])) ?: continue
            note("step ${steps[i].key}: tap '${label(node)}'")
            if (!tap(node)) note("  tap failed")
            step = i + 1
            scrollsThisStep = 0
            lastProgressAt = now
            if (step > lastIdx) return succeed()
            return schedule(SETTLE_MS)
        }

        // Nothing matched on this screen.
        if (now - lastProgressAt > STUCK_MS) {
            if (step == 0 && backsAtStart < 3) {
                // Waze may have reopened on some inner screen – back out toward the map.
                backsAtStart++
                note("menu not found, pressing back")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastProgressAt = now
                return schedule(SETTLE_MS)
            }
            if (scrollsThisStep < MAX_SCROLLS) {
                val scrollable = findScrollable(root)
                if (scrollable != null) {
                    scrollsThisStep++
                    note("scroll looking for ${steps[step].key}")
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    lastProgressAt = now - STUCK_MS / 2
                    return schedule(SETTLE_MS)
                }
                scrollsThisStep = MAX_SCROLLS
            }
        }
        schedule(TICK_MS)
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun succeed() {
        running = false
        prefs.mode = target
        note("done")
        prefs.lastLog = log.toString()
        VehicleWidgetProvider.updateAll(this)
        toast(getString(R.string.switched_to, modeName(target)))
        returnToMap(0)
    }

    private fun fail(reason: String) {
        running = false
        note("FAILED: $reason (stuck at ${walkSteps.getOrNull(step)?.key})")
        rootInActiveWindow?.let { note("visible texts: " + visibleTexts(it).joinToString(" | ")) }
        prefs.lastLog = log.toString()
        VehicleWidgetProvider.updateAll(this)
        toast(getString(R.string.switch_failed))
    }

    /** Press back until the main map (menu button visible) is shown, max 6 times. */
    private fun returnToMap(count: Int) {
        if (count >= 6) return
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null || root.packageName != WAZE) return@postDelayed
            if (findByLabels(root, prefs.labels(Step.MENU)) != null) return@postDelayed
            performGlobalAction(GLOBAL_ACTION_BACK)
            returnToMap(count + 1)
        }, 450)
    }

    // ---------- node helpers ----------

    private fun normalize(s: CharSequence?): String =
        s?.toString()
            ?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "") // bidi marks
            ?.trim()?.trimEnd(':')?.trim()?.lowercase()
            ?: ""

    private fun label(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: "?"

    private fun findByLabels(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (best == null && n.isVisibleToUser &&
                (normalize(n.text) in wanted || normalize(n.contentDescription) in wanted)
            ) best = n
        }
        return best
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n -> if (found == null && n.isScrollable && n.isVisibleToUser) found = n }
        return found
    }

    private fun visibleTexts(root: AccessibilityNodeInfo): List<String> {
        val out = LinkedHashSet<String>()
        walk(root) { n ->
            if (n.isVisibleToUser) {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += "[$it]" }
            }
        }
        return out.take(60)
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    /** Click the node or its nearest clickable ancestor; fall back to a real tap gesture. */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------- misc ----------

    private fun modeName(m: Mode) = getString(if (m == Mode.CAR) R.string.mode_car else R.string.mode_moto)

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun note(msg: String) {
        Log.d(TAG, msg)
        log.append(msg).append('\n')
    }
}
