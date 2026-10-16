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

    // Android can emit repeated ACTION_MOVE events with identical coordinates.
    // Forwarding those duplicates makes gesture velocity estimators see zero-speed
    // samples, which can make Phosh swipes feel sticky or fail to complete.
    // Filter only exact duplicate MOVE frames; preserve DOWN/UP/CANCEL and all
    // genuine multi-pointer movement.
    private var lastMoveX = FloatArray(0)
    private var lastMoveY = FloatArray(0)
    private var lastMovePointerCount = 0

    private fun isDuplicateMove(event: MotionEvent): Boolean {
        val count = event.pointerCount
        if (count == 0 || count != lastMovePointerCount ||
            lastMoveX.size != count || lastMoveY.size != count) return false
        for (i in 0 until count) {
            if (event.getX(i) != lastMoveX[i] || event.getY(i) != lastMoveY[i]) {
                return false
            }
        }
        return true
    }

    private fun rememberMove(event: MotionEvent) {
        val count = event.pointerCount
        if (lastMoveX.size != count) {
            lastMoveX = FloatArray(count)
            lastMoveY = FloatArray(count)
        }
        for (i in 0 until count) {
            lastMoveX[i] = event.getX(i)
            lastMoveY[i] = event.getY(i)
        }
        lastMovePointerCount = count
    }

    private fun clearMoveHistory() {
        lastMovePointerCount = 0
    }

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
        // Reclaim focus after Android overlays or wake-up, but never open the
        // keyboard merely because the user touched the bottom of the desktop.
        // The keyboard remains available through the existing three-finger gesture.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> clearMoveHistory()
            MotionEvent.ACTION_MOVE -> {
                if (isDuplicateMove(event)) return true
                rememberMove(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                clearMoveHistory()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !lorieView.hasFocus()) {
            lorieView.requestFocus()
        }
        return inputHandler.handleTouchEvent(lorieView, view, event)
    }
}
