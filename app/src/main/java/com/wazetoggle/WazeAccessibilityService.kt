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
    private var relaunched = false
    private var lastWazeTexts: List<String> = emptyList()
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
        relaunched = false
        lastWazeTexts = emptyList()
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
            // If an earlier "back" took us out of Waze, bring it back once.
            if (backsAtStart > 0 && !relaunched) {
                relaunched = true
                packageManager.getLaunchIntentForPackage(WAZE)?.let {
                    note("left Waze, relaunching")
                    startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
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

        // On the main map the menu is an icon-only "☰" button in the top corner, with no text
        // we can match. The map itself is recognised by its "Where to?" search bar.
        if (step == 0 && findByLabels(root, prefs.labels(Step.MAP)) != null) {
            val btn = findCornerButton(root)
            if (btn != null) {
                note("map: tap menu icon [${btn.contentDescription ?: btn.viewIdResourceName ?: "?"}]")
                tap(btn)
            } else {
                note("map: tap menu corner by position")
                tapCorner(root)
            }
            step = 1
            scrollsThisStep = 0
            lastProgressAt = now
            return schedule(SETTLE_MS)
        }

        // Nothing matched on this screen.
        if (now - lastProgressAt > STUCK_MS) {
            lastWazeTexts = visibleTexts(root)
            if (step == 0 && backsAtStart < 2) {
                // Waze reopened on some inner screen – back out toward the map.
                backsAtStart++
                note("map not found, pressing back")
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
        val texts = rootInActiveWindow?.takeIf { it.packageName == WAZE }?.let { visibleTexts(it) }
            ?: lastWazeTexts
        note("Waze screen texts: " + texts.joinToString(" | ").ifEmpty { "(none captured)" })
        prefs.lastLog = log.toString()
        VehicleWidgetProvider.updateAll(this)
        toast(getString(R.string.switch_failed))
    }

    /** Press back until the main map ("Where to?" visible) is shown, max 6 times. */
    private fun returnToMap(count: Int) {
        if (count >= 6) return
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null || root.packageName != WAZE) return@postDelayed
            if (findByLabels(root, prefs.labels(Step.MAP)) != null) return@postDelayed
            performGlobalAction(GLOBAL_ACTION_BACK)
            returnToMap(count + 1)
        }, 450)
    }

    // ---------- node helpers ----------

    private fun label(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: "?"

    /** Returns a visible node matching the labels; earlier labels in the list win. */
    private fun findByLabels(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { Labels.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return null
        val hits = HashMap<String, AccessibilityNodeInfo>()
        walk(root) { n ->
            if (n.isVisibleToUser) {
                for (key in listOf(Labels.normalize(n.text), Labels.normalize(n.contentDescription))) {
                    if (key in wanted && key !in hits) hits[key] = n
                }
            }
        }
        return wanted.firstNotNullOfOrNull { hits[it] }
    }

    /**
     * The smallest clickable element whose centre is in the top-left or top-right corner
     * (the ☰ button is top-left in both Hebrew and English Waze; right is a fallback for RTL builds).
     * The top-left one wins.
     */
    private fun findCornerButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        walk(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser) return@walk
            n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (area <= 0 || area > w.toLong() * h / 30) return@walk   // skip map / big panels
            if (r.centerY() > h * 0.15) return@walk
            val left = r.centerX() < w * 0.25
            val right = r.centerX() > w * 0.75
            if (!left && !right) return@walk
            // speaker/report buttons sit top-right, so prefer left strongly
            val score = area + if (left) 0 else 1_000_000_000L
            if (score < bestScore) { bestScore = score; best = n }
        }
        // Only accept the right side if its description looks like a menu.
        best?.let { b ->
            b.getBoundsInScreen(r)
            if (r.centerX() > w * 0.75) {
                val d = Labels.normalize(b.contentDescription)
                if (prefs.labels(Step.MENU).none { Labels.normalize(it) == d }) return null
            }
        }
        return best
    }

    /** Last resort: a tap where the ☰ button sits on the map screen (≈12% across, 8% down). */
    private fun tapCorner(root: AccessibilityNodeInfo) {
        val r = Rect().also { root.getBoundsInScreen(it) }
        val dm = resources.displayMetrics
        if (r.isEmpty) r.set(0, 0, dm.widthPixels, dm.heightPixels)
        tapAt(r.left + r.width() * 0.123f, r.top + dm.heightPixels * 0.08f)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
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
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    // ---------- misc ----------

    private fun modeName(m: Mode) = getString(if (m == Mode.CAR) R.string.mode_car else R.string.mode_moto)

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun note(msg: String) {
        Log.d(TAG, msg)
        log.append(msg).append('\n')
    }
}
