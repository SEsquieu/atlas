package com.grinningfrog.atlas.workspace.application

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Synthetic DOM input for detached TEST pages. Never installed in live apps or evidence of physical touch. */
internal object WorkspacePointerGesture {
    suspend fun drag(view: WebView, selector: String, value: String, cancel: Boolean): String {
        val options=JSONObject(value)
        WorkspaceApplicationContract.exactKeys(options,setOf("dx","dy","steps"),setOf("dx","dy"))
        val dx=options.getDouble("dx");val dy=options.getDouble("dy");val steps=options.optInt("steps",8)
        require(dx.isFinite() && dy.isFinite() && kotlin.math.abs(dx)<=1000 && kotlin.math.abs(dy)<=1000 && steps in 1..20) { "GESTURE_BOUNDS_INVALID" }
        val script="""(()=>{
          const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return 'missing';
          if(typeof PointerEvent!=='function')return 'unsupported_pointer_events';
          const r=e.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
          const dx=$dx,dy=$dy,steps=$steps;
          if(x<0||y<0||x+dx<0||y+dy<0||x>=innerWidth||y>=innerHeight||x+dx>=innerWidth||y+dy>=innerHeight)return 'outside_viewport';
          const id=719,proto=Element.prototype,captures=new Map();
          const original={set:proto.setPointerCapture,release:proto.releasePointerCapture,has:proto.hasPointerCapture};
          function event(type,px,py){return new PointerEvent(type,{bubbles:true,cancelable:true,composed:true,pointerId:id,pointerType:'touch',isPrimary:true,clientX:px,clientY:py,button:0,buttons:(type==='pointerup'||type==='pointercancel')?0:1});}
          // Synthetic events have no native active pointer. Capture is scoped to this TEST gesture only.
          proto.setPointerCapture=function(p){if(p===id){captures.set(p,this);this.dispatchEvent(event('gotpointercapture',x,y));}else original.set.call(this,p);};
          proto.releasePointerCapture=function(p){if(p===id){const target=captures.get(p);captures.delete(p);target?.dispatchEvent(event('lostpointercapture',x,y));}else original.release.call(this,p);};
          proto.hasPointerCapture=function(p){return p===id?captures.get(p)===this:original.has.call(this,p);};
          try{
            e.dispatchEvent(event('pointerdown',x,y));
            for(let i=1;i<=steps;i++){const px=x+dx*i/steps,py=y+dy*i/steps;const target=captures.get(id)||document.elementFromPoint(px,py)||document;target.dispatchEvent(event('pointermove',px,py));}
            (captures.get(id)||document.elementFromPoint(x+dx,y+dy)||document).dispatchEvent(event(${if(cancel) "'pointercancel'" else "'pointerup'"},x+dx,y+dy));
            return 'ok';
          }finally{
            const target=captures.get(id);captures.delete(id);target?.dispatchEvent(event('lostpointercapture',x+dx,y+dy));
            proto.setPointerCapture=original.set;proto.releasePointerCapture=original.release;proto.hasPointerCapture=original.has;
          }
        })()"""
        return suspendCancellableCoroutine { c -> view.evaluateJavascript(script) {if(c.isActive)c.resume(it)} }
    }
}
