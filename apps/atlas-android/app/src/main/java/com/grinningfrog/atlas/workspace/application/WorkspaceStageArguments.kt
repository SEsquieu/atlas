package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject

/** Recover only unambiguous misplaced source entries; package validation still owns paths and bounds. */
internal object WorkspaceStageArguments {
    fun normalize(input: JSONObject): JSONObject {
        val args=JSONObject(input.toString())
        val files=args.optJSONObject("files") ?: JSONObject()
        require(!args.has("files") || args.optJSONObject("files")!=null) { "files must be an object" }
        val moved=mutableListOf<String>()
        for(key in args.keys().asSequence().toList()) {
            if(!key.contains('/') || !(args.get(key) is String || args.isNull(key)))continue
            require(!files.has(key)) { "Conflicting source entry: $key; submit it only inside files" }
            files.put(key,args.get(key));args.remove(key);moved.add(key)
        }
        if(moved.isNotEmpty())args.put("files",files)
        return args
    }
}
