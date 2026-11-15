// Input injection on a given display. Approach adapted from scrcpy
// (InputManager.java, Controller.java), Apache-2.0.
package app.carlink.engine

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

object Input {
    private const val INJECT_ASYNC = 0

    private val manager: InputManager by lazy {
        Shell.context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }
    private val injectMethod by lazy {
        InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    fun inject(event: InputEvent, displayId: Int): Boolean {
        setDisplayIdMethod.invoke(event, displayId)
        return injectMethod.invoke(manager, event, INJECT_ASYNC) as Boolean
    }

    fun key(displayId: Int, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val e = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(e, displayId)
        }
    }
}

/** One pointer in a gesture event. */
data class Pointer(val id: Int, val x: Float, val y: Float)

/** A touch event to inject: MotionEvent action (with pointer index) and all active pointers. */
data class Gesture(val action: Int, val downTime: Long, val pointers: List<Pointer>)

/**
 * Pure multi-touch state for one pane: per-pointer messages in, MotionEvent
 * actions out. No Android calls, so it's unit-tested on the JVM.
 */
class TouchState {
    companion object {
        val POINTER_IDS = 0..9
        // MotionEvent constants, inlined so this class needs no Android runtime.
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
        const val INDEX_SHIFT = 8
    }

    private val active = ArrayList<Pointer>() // index order = MotionEvent pointer index
    private var downTime = 0L

    fun handle(action: String, id: Int, x: Float, y: Float, now: Long): Gesture? {
        if (id !in POINTER_IDS) return null
        val index = active.indexOfFirst { it.id == id }
        return when (action) {
            "down" -> {
                if (index >= 0) return null
                if (active.isEmpty()) downTime = now
                active += Pointer(id, x, y)
                val i = active.size - 1
                gesture(if (i == 0) DOWN else POINTER_DOWN or (i shl INDEX_SHIFT))
            }
            "move" -> {
                if (index < 0) return null
                active[index] = Pointer(id, x, y)
                gesture(MOVE)
            }
            "up" -> {
                if (index < 0) return null
                active[index] = Pointer(id, x, y)
                val g = gesture(if (active.size == 1) UP else POINTER_UP or (index shl INDEX_SHIFT))
                active.removeAt(index)
                g
            }
            "cancel" -> {
                if (active.isEmpty()) return null
                gesture(CANCEL).also { active.clear() }
            }
            else -> null
        }
    }

    private fun gesture(action: Int) = Gesture(action, downTime, active.toList())
}

/** Injects one pane's touches on its display. */
class Touch(private val displayId: Int) {
    private val state = TouchState()

    fun handle(action: String, id: Int, x: Float, y: Float) {
        val g = state.handle(action, id, x, y, SystemClock.uptimeMillis()) ?: return
        val props = Array(g.pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                this.id = g.pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(g.pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = g.pointers[i].x
                this.y = g.pointers[i].y
                pressure = 1f
                size = 1f
            }
        }
        val e = MotionEvent.obtain(g.downTime, SystemClock.uptimeMillis(), g.action, props.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        Input.inject(e, displayId)
        e.recycle()
    }
}
