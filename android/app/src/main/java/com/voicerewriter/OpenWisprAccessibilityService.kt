package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        /** Hold this long on a volume key, with a text field focused, to start dictating. */
        private const val TRIGGER_HOLD_MS = 400L

        /**
         * Ends a hold-to-talk take when the trigger key is released. [RewriteActivity] sets this
         * while its recorder is up, and clears it as soon as the take is over.
         */
        @Volatile
        var dictationStopper: (() -> Unit)? = null

        /**
         * True while a trigger key is still held after it fired a dictation. [RewriteActivity]
         * reads this the moment its recorder is up: a release that beat the activity there has to
         * end the take, not leave it running with nothing left able to stop it.
         */
        @Volatile
        var triggerHeld = false
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingText: String? = null
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)

    // ---- volume-key trigger state (see onKeyEvent) ----

    /** Pending hold-to-talk timer for the key currently down; null when no key is down. */
    private var triggerTimer: Runnable? = null
    /** Key code that already fired a dictation, so its release knows to end the take. */
    private var firedKeyCode = 0
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
     * Hold either volume key, with a text field focused or the keyboard up, to dictate. The key is
     * consumed from the moment it goes down so a hold never ramps the volume; a press that turns
     * out to be an ordinary tap is handed back to the system in [replayVolume]. Releasing ends a
     * hold-to-talk take, which is the point of the gesture — no finger pinned to a floating button.
     *
     * Needs `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in
     * `res/xml/accessibility_service_config.xml`; without them the framework never calls this.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // A release ending a take we already started is handled before the arming check: once our
        // own sheet is up, the conditions that armed the trigger are no longer true, but this is
        // the event that stops the recorder.
        if (event.action == KeyEvent.ACTION_UP && firedKeyCode == keyCode) {
            cancelTriggerTimer()
            firedKeyCode = 0
            triggerHeld = false
            dictationStopper?.let { main.post(it) }
            return true
        }

        if (!triggerArmed()) return false // not our gesture — leave the volume keys alone

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val timer = Runnable { fireTrigger(keyCode) }
                    cancelTriggerTimer()
                    triggerTimer = timer
                    main.postDelayed(timer, TRIGGER_HOLD_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                cancelTriggerTimer()
                // Released before the hold threshold, so this was a normal volume tap that the
                // system never saw.
                replayVolume(keyCode)
                return true
            }
        }
        return false
    }

    /**
     * Only armed while a host app has a text field focused (or its keyboard is up) and our own
     * sheet is not on screen. Everywhere else the volume keys behave exactly as they always did.
     */
    private fun triggerArmed(): Boolean =
        (hostFieldFocused || imeVisible) && !ourModal && dictationStopper == null

    private fun fireTrigger(keyCode: Int) {
        triggerTimer = null
        if (!triggerArmed()) return
        firedKeyCode = keyCode
        triggerHeld = true
        vibrateTick() // lands before the sheet can, so the gesture feels immediate
        startActivity(RewriteActivity.dictateIntent(this, pushToTalk = true))
    }

    /**
     * Hands an ordinary volume tap back to the system. Consuming the key while deciding whether it
     * was a hold also suppressed the system's own volume change, so the change has to be re-issued
     * here. [AudioManager.adjustStreamVolume] on the music stream with the system UI shown is what
     * the framework picks when media is what the user is hearing; a call or Do Not Disturb would
     * have chosen something else. That approximation is the price of the trigger owning the volume
     * keys while a field is focused.
     */
    private fun replayVolume(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {}
    }

    private fun cancelTriggerTimer() {
        triggerTimer?.let { main.removeCallbacks(it) }
        triggerTimer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTriggerTimer()
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
