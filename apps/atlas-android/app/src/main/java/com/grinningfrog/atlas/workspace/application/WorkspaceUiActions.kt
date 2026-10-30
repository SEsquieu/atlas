package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject

/** Fixed host actions shared by batch DOM testing and persistent visual instances. */
internal object WorkspaceUiActions {
    fun script(step: JSONObject): String {
        val action=step.getString("action")
        val selector=JSONObject.quote(step.getString("selector"))
        val value=JSONObject.quote(step.optString("value"))
        return """(()=>{const matches=document.querySelectorAll($selector);const e=matches[0];if(${"\"$action\""}==='assert_absent')return matches.length===0?'ok':'present';if(${"\"$action\""}==='assert_count')return JSON.stringify({status:matches.length===Number($value)?'ok':'count_mismatch',expected_count:Number($value),actual_count:matches.length});if(!e)return 'missing';if(${"\"$action\""}==='set_checked'){if(e.disabled)return 'disabled';if(e.tagName!=='INPUT'||!['checkbox','radio'].includes(e.type))return 'not_checkable';e.checked=($value==='true');e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}if(${"\"$action\""}==='click'){if(e.disabled)return 'disabled';if(typeof e.click==='function')e.click();else e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return 'ok';}if(${"\"$action\""}==='input'||${"\"$action\""}==='select'){if(e.disabled)return 'disabled';if(${"\"$action\""}==='select'&&e.tagName!=='SELECT')return 'not_select';e.value=$value;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({status:e.value===$value?'ok':'value_mismatch',requested_value:$value,actual_value:e.value,input_type:e.type||e.tagName,min:e.min||null,max:e.max||null,step:e.step||null,valid:e.validity?e.validity.valid:null});}if(${"\"$action\""}==='assert_value')return e.value===$value?'ok':'value_mismatch';if(${"\"$action\""}==='assert_visible'||${"\"$action\""}==='assert_hidden'){const shown=!e.hidden&&e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden';return shown===(${"\"$action\""}==='assert_visible')?'ok':'visibility_mismatch';}if(${"\"$action\""}==='assert_text_exact')return (e.textContent||'').trim()===$value?'ok':'mismatch';if(${"\"$action\""}==='assert_attribute'){const a=JSON.parse($value);return e.getAttribute(a.name)===a.equals?'ok':'attribute_mismatch';}return (e.textContent||'').includes($value)?'ok':'mismatch';})()"""
    }
}
