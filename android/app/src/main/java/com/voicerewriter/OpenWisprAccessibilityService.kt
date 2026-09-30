package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Makes "insert the rewrite into the field I was typing in" possible. An overlay
 * cannot write into other apps — only an IME, the owning app, or an accessibility
 * service can.
 *
 * Insertion is event-driven: RewriteActivity hands us the text and finishes; once
 * the *host* app regains window focus (we ignore our own windows), we paste the
 * clipboard into its focused editable field. Timed retries back this up in case
 * no focus event fires. This avoids the trap of inserting while our own activity
 * is still the active window.
 */
class OpenWisprAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpenWisprA11y"

        @Volatile
        private var instance: OpenWisprAccessibilityService? = null

        /**
         * Package of the last non-self app to take window focus. The dictation
         * pipeline reads this to decide whether the target is a code/terminal field
         * (where spoken "dot"/"slash"/"dash" should pass through as words).
         */
        @Volatile
        var lastHostPackage: String? = null
            private set

        /**
         * System surfaces whose window-state changes must NOT be mistaken for the
         * dictation target. The status bar / notification shade / nav bar all report
         * as "com.android.systemui", and the keyboard reports as an input-method
         * package — neither is the app the user is dictating into, so letting them
         * overwrite [lastHostPackage] is what made history entries read "System UI".
         */
        private val NON_HOST_PACKAGES = setOf(
            "com.android.systemui", "android", "com.android.launcher", "com.sec.android.app.launcher",
        )

        private fun isHostPackage(pkg: String, self: String): Boolean =
            pkg != self && pkg !in NON_HOST_PACKAGES &&
                !pkg.contains("inputmethod") && !pkg.contains("honeyboard")

        /** True when the user has enabled the service in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        /**
         * Stage [text] on the clipboard and insert it into the host app's focused
         * field as soon as that app is back in front. Returns true if the service
         * is running (so the caller knows auto-insert will be attempted).
         * Call from the main thread.
         */
        fun enqueueInsert(text: String): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text)
            return true
        }

        /** Re-check focus now (e.g. when the bubble (re)starts) so it shows if a field is already focused. */
        fun reevaluate() {
            val svc = instance ?: return
            svc.main.post { svc.evaluateFieldFocus() }
        }

        /**
         * Longest gap between the first press's release and the second press's press for the pair
         * to count as a double press. Deliberately tight: two taps meant as two volume steps are
         * slower than this, and a double press meant as a trigger is faster.
         */
        private const val DOUBLE_PRESS_MS = 300L

        /**
         * A press held longer than this was the system ramping the volume (the way most people
         * turn it down fast), not a tap, so it never pairs with the press that follows it.
         */
        private const val TAP_MAX_MS = 200L

        /**
         * The live dictation, so a second double press can end a take without the user having to
         * reach for the sheet. [RewriteActivity] sets this while its recorder is up and clears it
         * as soon as the take is over; it doubles as "a take is running" for the trigger.
         */
        @Volatile
        var dictationStopper: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingText: String? = null
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)

    // ---- volume-key double-press trigger state (see onKeyEvent) ----

    /** Volume key of the last completed press, or 0 when there is no press to pair with. */
    private var tapKeyCode = 0
    /** When that press went down, to tell a tap from a hold. */
    private var tapDownAt = 0L
    /** When that press came up, to measure the gap to the next press. */
    private var tapUpAt = 0L
    /** Second press of a pair already fired, so its release is swallowed with the press. */
    private var swallowKeyCode = 0
    /** A host app has a focused editable field. */
    @Volatile private var hostFieldFocused = false
    /** A keyboard window is on screen (the "keyboard is up" half of the arm condition). */
    @Volatile private var imeVisible = false
    /** Our own recording/transform sheet is on screen, so a second take must not fire. */
    @Volatile private var ourModal = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
        // Now that focus detection is available, let the bubble switch to its
        // "only show on text fields" behavior (it starts always-visible without us).
        BubbleService.instance?.refreshGating()
        main.post { evaluateFieldFocus() }
    }

    /** Debounced re-check of whether a host text field is focused (drives the bubble). */
    private val fieldCheck = Runnable { evaluateFieldFocus() }

    /**
     * Tell the bubble whether the foreground app currently has a focused editable
     * field. Skips our own windows so the recording sheet doesn't flap the bubble.
     */
    private fun evaluateFieldFocus() {
        // Scan all interactive windows, not just rootInActiveWindow — across an app
        // switch the "active window" can be null/transient, which left the bubble
        // stuck. The focused editable lives in whichever window holds input focus.
        val wins = try { windows } catch (_: Exception) { null }
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return
            if (root.packageName == packageName) return
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editable = f != null && f.isEditable
            @Suppress("DEPRECATION") f?.recycle()
            Log.d(TAG, "fieldFocus(fallback) editable=$editable")
            hostFieldFocused = editable
            imeVisible = false
            ourModal = false
            BubbleService.instance?.setFieldFocused(editable)
            return
        }
        var editable = false
        var ourModalActive = false
        var ime = false
        for (w in wins) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ime = true
            val root = w.root ?: continue
            if (root.packageName == packageName) {
                // Our recording/transform sheet (an activity) — don't flap the bubble.
                // The bubble's own overlay window is harmless; only the modal counts.
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) ourModalActive = true
                continue
            }
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                if (f.isEditable) editable = true
                @Suppress("DEPRECATION") f.recycle()
            }
            if (editable) break
        }
        // Drives the volume-key trigger: a focused host field, or a keyboard on screen. Same
        // "contextual, like a keyboard key" gate the bubble used for its own visibility.
        hostFieldFocused = editable
        imeVisible = ime
        ourModal = ourModalActive
        if (ourModalActive) return // leave the bubble as-is while our sheet is up
        Log.d(TAG, "fieldFocus editable=$editable host=$lastHostPackage")
        BubbleService.instance?.setFieldFocused(editable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track the foreground host app (cheap: window changes are infrequent) so the
        // dictation pipeline can adapt normalization to code/terminal fields.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && isHostPackage(pkg, packageName)) lastHostPackage = pkg
        }
        // Drive the field-gated bubble: re-check focus on any event that can change it
        // (coalesced — content-changed can fire in bursts).
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                main.removeCallbacks(fieldCheck)
                main.postDelayed(fieldCheck, 120)
            }
        }
        if (pendingText == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) attemptInsert()
            }
        }
    }

    override fun onInterrupt() {}

    // ---------------- volume-key dictation trigger ----------------

    /**
     * A quick double press of either volume key — with a text field focused or the keyboard up —
     * starts a dictation, and another double press while it runs ends it.
     *
     * Nothing is swallowed on the way in. A single tap and a held key (how people actually turn
     * the volume down fast) reach the system exactly as they always did, because the first press
     * of the pair is passed straight through; only the second press is consumed, so a triggered
     * pair costs one volume step instead of two. The gap that counts as a double press is
     * [DOUBLE_PRESS_MS], tight enough that two deliberate volume steps do not qualify.
     *
     * Needs `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in
     * `res/xml/accessibility_service_config.xml`; without them the framework never calls this.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (!triggerArmed()) {
            forgetTap()
            return false
        }

        val now = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Key repeat means the key is being held: the system is ramping the volume, so
                // this press is a hold and can never pair with the next one.
                if (event.repeatCount > 0) {
                    forgetTap()
                    return false
                }
                if (tapKeyCode == keyCode && now - tapUpAt <= DOUBLE_PRESS_MS) {
                    return fireTrigger(keyCode)
                }
                tapKeyCode = keyCode
                tapDownAt = now
                return false
            }
            KeyEvent.ACTION_UP -> {
                if (swallowKeyCode == keyCode) {
                    swallowKeyCode = 0
                    return true
                }
                if (tapKeyCode == keyCode) {
                    tapUpAt = now
                    // A hold was a ramp, not a tap: it must not pair with whatever comes next.
                    if (now - tapDownAt > TAP_MAX_MS) tapKeyCode = 0
                }
                return false
            }
        }
        return false
    }

    /**
     * Watched only where the gesture means something: a host app's text field is focused, a
     * keyboard is up, or a take is already running (so a second double press can end it).
     * Everywhere else the volume keys are not even looked at.
     */
    private fun triggerArmed(): Boolean = hostFieldFocused || imeVisible || dictationStopper != null

    /**
     * The second press of a pair. Ends the take if one is running; otherwise starts a dictation,
     * unless our own sheet is on screen for reasons of its own (a result awaiting its edit
     * window), where a volume key means nothing. Returns whether the press was consumed.
     */
    private fun fireTrigger(keyCode: Int): Boolean {
        forgetTap()
        val stop = dictationStopper
        if (stop == null && ourModal) return false
        swallowKeyCode = keyCode
        vibrateTick() // confirmation lands before the sheet can, so the gesture feels immediate
        if (stop != null) main.post(stop)
        else startActivity(RewriteActivity.dictateIntent(this, pushToTalk = false))
        return true
    }

    /** Drops any half-finished press pair (disarmed keys, a hold, a key that already fired). */
    private fun forgetTap() {
        tapKeyCode = 0
        swallowKeyCode = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(fieldCheck)
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String) {
        // Note: we deliberately do NOT stage the clipboard here. The common insert path
        // writes the text straight into the field (ACTION_SET_TEXT) without touching the
        // clipboard, so a successful dictation no longer clobbers what the user had copied.
        // The clipboard is only used as a fallback (see attemptInsert / the give-up branch).
        pendingText = text
        main.removeCallbacksAndMessages(null)
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ attemptInsert() }, delay)
        // Give up after the last retry: no field ever focused — fall back to a clipboard copy.
        main.postDelayed({
            val t = pendingText
            if (t != null) {
                pendingText = null
                setClipboard(t)
            }
        }, retryDelays.last() + 300)
    }

    /** Try once to insert into the host app's focused editable field. */
    private fun attemptInsert() {
        val text = pendingText ?: return
        val node = findHostFocusedEditable() ?: return
        val ok = try {
            // Prefer a clipboard-free splice at the cursor; fall back to paste only when
            // we can't determine the cursor (e.g. some WebView fields).
            insertAtCursor(node, text) || pasteViaClipboard(node, text)
        } catch (e: Exception) {
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (ok) {
            Log.i(TAG, "inserted into host field")
            pendingText = null
            main.removeCallbacksAndMessages(null)
            // The haptic tick is the confirmation. A toast on top of text visibly appearing in
            // the field is telling the user something they can already see.
            vibrateTick()
        }
    }

    /**
     * Clipboard-free insert: splice [insert] in at the cursor (replacing any active
     * selection) via ACTION_SET_TEXT, then place the cursor after it. Returns false when
     * the cursor can't be determined in a non-empty field, so the caller can fall back to
     * paste. This is the path that keeps the clipboard untouched on a normal dictation.
     */
    private fun insertAtCursor(node: AccessibilityNodeInfo, insert: String): Boolean {
        val current = node.text?.toString() ?: ""
        val selA = node.textSelectionStart
        val selB = node.textSelectionEnd
        val (start, end) = when {
            selA in 0..current.length && selB in 0..current.length ->
                minOf(selA, selB) to maxOf(selA, selB)
            current.isEmpty() -> 0 to 0
            else -> return false // unknown cursor in a non-empty field — let paste handle it
        }
        val newText = current.substring(0, start) + insert + current.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val cursor = start + insert.length
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    /** Fallback insert: stage on the clipboard and paste. Honors cursor position, but the
     *  text is necessarily left on the clipboard (only used when [insertAtCursor] can't). */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        setClipboard(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** Focused editable node in the active window, only if it's NOT our own app. */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // our sheet is still up
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION") focused?.recycle()
        return null
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    /** Light confirmation buzz when text lands in the field. */
    private fun vibrateTick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
}
