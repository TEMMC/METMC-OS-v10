package com.nativOS.x11

import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Routes Android touch, mouse, and keyboard events into the embedded X server. */
class X11InputController(private val lorieView: LorieView) {
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !lorieView.hasFocus()) {
            lorieView.requestFocus()
        }
        return inputHandler.handleTouchEvent(lorieView, view, event)
    }
}
