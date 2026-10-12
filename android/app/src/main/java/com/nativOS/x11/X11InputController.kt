package com.nativOS.x11

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Routes Android touch, mouse, and keyboard events into the embedded X server. */
class X11InputController(private val lorieView: LorieView) {
    // X11 does not expose every Linux widget's text-focus state to Android's IME.
    // On a phone, tapping the lower input line is a useful terminal-focused fallback.
    private var autoKeyboardRequested = false
    private var lastAutoKeyboardAt = 0L
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    init {
        // Direct XInput touch is not handled by every nested Wayland compositor.
        // Simulated touch preserves phone-style absolute positioning using mouse events.
        MainActivity.getPrefs().touchMode.put(
            TouchInputHandler.InputMode.SIMULATED_TOUCH.toString()
        )
        inputHandler.reloadPreferences(MainActivity.getPrefs())
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)

        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        // Reclaim focus on the first tap after Android overlays or a screen
        // wake-up so that touch and keyboard input reach the Linux session.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!lorieView.hasFocus()) lorieView.requestFocus()
            val nearBottomInputLine = event.pointerCount == 1 && view.height > 0 && event.y >= view.height * 0.76f
            if (nearBottomInputLine && !autoKeyboardRequested && SystemClock.elapsedRealtime() - lastAutoKeyboardAt > 900L) {
                lastAutoKeyboardAt = SystemClock.elapsedRealtime()
                autoKeyboardRequested = true
                runCatching { MainActivity.toggleKeyboardVisibility(view.context) }
            } else if (!nearBottomInputLine) {
                autoKeyboardRequested = false
            }
        }
        return inputHandler.handleTouchEvent(lorieView, view, event)
    }
}
