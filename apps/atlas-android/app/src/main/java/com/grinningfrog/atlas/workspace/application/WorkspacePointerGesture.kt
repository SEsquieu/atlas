package com.grinningfrog.atlas.workspace.application

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Touch events enter Chromium's real pointer path, including SVG and pointer capture. Main thread only. */
internal object WorkspacePointerGesture {
    suspend fun drag(view: WebView, selector: String, value: String, cancel: Boolean): String {
        val options=JSONObject(value)
        WorkspaceApplicationContract.exactKeys(options,setOf("dx","dy","steps"),setOf("dx","dy"))
        val dx=options.getDouble("dx");val dy=options.getDouble("dy");val steps=options.optInt("steps",8)
        require(dx.isFinite() && dy.isFinite() && kotlin.math.abs(dx)<=1000 && kotlin.math.abs(dy)<=1000 && steps in 1..20) { "GESTURE_BOUNDS_INVALID" }
        val raw=suspendCancellableCoroutine<String> { c -> view.evaluateJavascript("""(()=>{const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return null;const r=e.getBoundingClientRect();return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight});})()""") {if(c.isActive)c.resume(it)} }
        if(raw=="null")return "\"missing\""
        val bounds=JSONObject(JSONArray("[$raw]").getString(0))
        val x=bounds.getDouble("x");val y=bounds.getDouble("y")
        if(x !in 0.0..bounds.getDouble("w") || y !in 0.0..bounds.getDouble("h") || x+dx !in 0.0..bounds.getDouble("w") || y+dy !in 0.0..bounds.getDouble("h"))return "\"outside_viewport\""
        val sx=view.width/bounds.getDouble("w");val sy=view.height/bounds.getDouble("h")
        val down=SystemClock.uptimeMillis()
        var lastX=x;var lastY=y;var ended=false
        fun send(action: Int) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,(lastX*sx).toFloat(),(lastY*sy).toFloat(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try {view.dispatchTouchEvent(event)}finally{event.recycle()}
        }
        try {
            send(MotionEvent.ACTION_DOWN)
            for(i in 1..steps) {delay(20);lastX=x+dx*i/steps;lastY=y+dy*i/steps;send(MotionEvent.ACTION_MOVE)}
            send(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP);ended=true
        } finally {if(!ended)send(MotionEvent.ACTION_CANCEL)}
        return "\"ok\""
    }
}
