package com.grinningfrog.atlas.workspace.visual

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import com.grinningfrog.atlas.workspace.application.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Inputs are confined to this WebView. Never inject into Android or request an IME. */
internal object WorkspaceVisualInteractions {
    val actions = setOf("click", "input", "select", "set_checked", "assert_text", "assert_text_exact", "assert_value", "assert_visible", "assert_hidden", "assert_absent", "assert_count", "assert_attribute", "tap", "swipe", "key", "scroll")
    fun validate(step: JSONObject) {
        WorkspaceApplicationContract.exactKeys(step, setOf("action", "selector", "value", "criterion"), setOf("action"))
        val action = step.getString("action"); require(action in actions) { "UNSUPPORTED_VISUAL_ACTION" }
        require(step.optString("selector").length <= 128 && step.optString("value").length <= 2000)
        if(step.has("criterion")) require(action.startsWith("assert_") && step.getString("criterion").trim().length in 1..200)
        if(action in setOf("tap", "swipe", "scroll")) {
            val v = step.getJSONObject("value")
            val names = if(action=="tap") setOf("x", "y") else if(action=="scroll") setOf("dx", "dy") else setOf("x", "y", "to_x", "to_y", "duration_ms")
            WorkspaceApplicationContract.exactKeys(v, names, names - "duration_ms")
            for(name in names - "duration_ms") require(v.get(name) is Number && v.getDouble(name).isFinite() && kotlin.math.abs(v.getDouble(name)) <= 8192)
            if(action=="swipe") require(v.optInt("duration_ms", 300) in 50..1000)
        } else if(action=="key") require(step.getString("value") in setOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter", "Escape", " ", "w", "a", "s", "d"))
        else {
            require(step.has("selector") && step.getString("selector").isNotBlank())
            WorkspaceUiStepSchema.normalize(step, 0)
            require(step.has("value") || action in setOf("click", "assert_visible", "assert_hidden", "assert_absent"))
            if(action=="set_checked")require(step.getString("value") in setOf("true", "false"))
            if(action=="assert_attribute") {
                val a=JSONObject(step.getString("value")); WorkspaceApplicationContract.exactKeys(a,setOf("name","equals"),setOf("name","equals"))
                require(a.getString("name").matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && a.get("equals") is String)
            }
        }
    }
    private suspend fun evaluate(view: WebView, script: String): String = withTimeout(3_000) {
        val response = CompletableDeferred<String>(); view.evaluateJavascript(script) { response.complete(it) }
        response.await().also { require(it.length <= 64 * 1024) }
    }
    suspend fun run(view: WebView, step: JSONObject): JSONObject {
        validate(step)
        val action=step.getString("action")
        val raw = when(action) {
            "tap", "swipe" -> {
                val v=step.getJSONObject("value")
                val viewport=JSONObject(JSONArray("[${evaluate(view,"JSON.stringify({w:innerWidth,h:innerHeight})")}]").getString(0))
                fun cssX(name: String): Float { val css=v.getDouble(name);require(css>=0 && css<viewport.getDouble("w"));return (css*view.width/viewport.getDouble("w")).toFloat() }
                fun cssY(name: String): Float { val css=v.getDouble(name);require(css>=0 && css<viewport.getDouble("h"));return (css*view.height/viewport.getDouble("h")).toFloat() }
                val x=cssX("x");val y=cssY("y");val toX=if(action=="swipe")cssX("to_x") else x;val toY=if(action=="swipe")cssY("to_y") else y
                val down=SystemClock.uptimeMillis()
                fun send(kind: Int, atX: Float, atY: Float) { val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,atX,atY,0);try { view.dispatchTouchEvent(event) } finally { event.recycle() } }
                var released=false
                try {
                    send(MotionEvent.ACTION_DOWN,x,y)
                    if(action=="swipe") {
                        val count=20;val duration=v.optInt("duration_ms",300)
                        for(i in 1..count) { delay((duration/count).toLong());send(MotionEvent.ACTION_MOVE,x+(toX-x)*i/count,y+(toY-y)*i/count) }
                    } else delay(40)
                    send(MotionEvent.ACTION_UP,toX,toY);released=true
                } finally { if(!released)send(MotionEvent.ACTION_CANCEL,toX,toY) }
                "\"ok\""
            }
            "scroll" -> { val v=step.getJSONObject("value");evaluate(view,"(()=>{scrollBy(${v.getDouble("dx")},${v.getDouble("dy")});return 'ok'})()") }
            "key" -> { val key=JSONObject.quote(step.getString("value"));evaluate(view,"(()=>{const e=document.activeElement||document.body;for(const type of ['keydown','keyup'])e.dispatchEvent(new KeyboardEvent(type,{key:$key,bubbles:true,cancelable:true}));return 'ok'})()") }
            else -> evaluate(view,WorkspaceUiActions.script(step))
        }
        val decoded=runCatching { JSONArray("[$raw]").get(0) }.getOrNull()
        val evidence=(decoded as? String)?.let {runCatching {JSONObject(it)}.getOrNull()}
        val passed=decoded=="ok" || evidence?.optString("status")=="ok"
        return JSONObject().put("action",action).put("selector",step.optString("selector")).put("passed",passed).put("outcome",decoded?.toString()?.take(300))
            .put("evidence",evidence).put("criterion",step.optString("criterion").takeIf {it.isNotBlank()})
            .put("input_scope",if(action in setOf("tap","swipe"))"android_motion_events_in_test_webview" else "fixed_dom_action_synthetic_events")
    }
}
