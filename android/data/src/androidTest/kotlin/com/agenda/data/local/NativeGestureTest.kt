package com.agenda.data.local

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly invoked by the synthetic emulator review; cannot inject on physical phones. */
class NativeGestureTest {
    @Test fun longPressDrag() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("agendaGesture")=="true" && Build.HARDWARE in setOf("ranchu","goldfish"))
        val fromX=arguments.getString("fromX")!!.toFloat();val fromY=arguments.getString("fromY")!!.toFloat()
        val toX=arguments.getString("toX")!!.toFloat();val toY=arguments.getString("toY")!!.toFloat()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event,true));event.recycle()
        }
        send(MotionEvent.ACTION_DOWN,fromX,fromY);SystemClock.sleep(700)
        for(i in 1..16) {send(MotionEvent.ACTION_MOVE,fromX+(toX-fromX)*i/16,fromY+(toY-fromY)*i/16);SystemClock.sleep(16)}
        send(MotionEvent.ACTION_UP,toX,toY)
    }
}
