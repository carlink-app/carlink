package app.carlink.engine

import app.carlink.engine.TouchState.Companion.CANCEL
import app.carlink.engine.TouchState.Companion.DOWN
import app.carlink.engine.TouchState.Companion.MOVE
import app.carlink.engine.TouchState.Companion.POINTER_DOWN
import app.carlink.engine.TouchState.Companion.POINTER_UP
import app.carlink.engine.TouchState.Companion.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchStateTest {
    private val t = TouchState()
    private fun ids(g: Gesture?) = g!!.pointers.map { it.id }

    @Test fun singleTap() {
        assertEquals(DOWN, t.handle("down", 0, 1f, 2f, 100)!!.action)
        val move = t.handle("move", 0, 3f, 4f, 110)!!
        assertEquals(MOVE, move.action)
        assertEquals(Pointer(0, 3f, 4f), move.pointers.single())
        val up = t.handle("up", 0, 5f, 6f, 120)!!
        assertEquals(UP, up.action)
        assertEquals(100, up.downTime)
        assertEquals(Pointer(0, 5f, 6f), up.pointers.single())
    }

    @Test fun secondFingerAndLiftingTheFirst() {
        t.handle("down", 3, 0f, 0f, 1)
        val second = t.handle("down", 7, 10f, 10f, 2)!!
        assertEquals(POINTER_DOWN or (1 shl 8), second.action)
        assertEquals(listOf(3, 7), ids(second))
        val liftFirst = t.handle("up", 3, 0f, 0f, 3)!!
        assertEquals(POINTER_UP or (0 shl 8), liftFirst.action)
        assertEquals(listOf(3, 7), ids(liftFirst)) // the lifting pointer is still in the event
        assertEquals(listOf(7), ids(t.handle("move", 7, 11f, 11f, 4)))
        assertEquals(UP, t.handle("up", 7, 11f, 11f, 5)!!.action)
    }

    @Test fun thirdFingerIndex() {
        t.handle("down", 0, 0f, 0f, 1)
        t.handle("down", 1, 0f, 0f, 1)
        assertEquals(POINTER_DOWN or (2 shl 8), t.handle("down", 2, 0f, 0f, 1)!!.action)
        assertEquals(POINTER_UP or (1 shl 8), t.handle("up", 1, 0f, 0f, 2)!!.action)
    }

    @Test fun invalidInputIsIgnored() {
        assertNull(t.handle("move", 0, 0f, 0f, 1)) // unknown pointer
        assertNull(t.handle("up", 0, 0f, 0f, 1))
        assertNull(t.handle("down", 10, 0f, 0f, 1)) // id out of range
        assertNull(t.handle("down", -1, 0f, 0f, 1))
        assertNull(t.handle("tap", 0, 0f, 0f, 1)) // unknown action
        t.handle("down", 0, 0f, 0f, 1)
        assertNull(t.handle("down", 0, 0f, 0f, 2)) // duplicate down
    }

    @Test fun cancelClearsEverything() {
        assertNull(t.handle("cancel", 0, 0f, 0f, 1))
        t.handle("down", 0, 0f, 0f, 1)
        t.handle("down", 1, 0f, 0f, 1)
        assertEquals(CANCEL, t.handle("cancel", 0, 0f, 0f, 2)!!.action)
        assertNull(t.handle("cancel", 0, 0f, 0f, 3))
        val fresh = t.handle("down", 1, 0f, 0f, 50)!!
        assertEquals(DOWN, fresh.action)
        assertEquals(50, fresh.downTime)
    }
}
