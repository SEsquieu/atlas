package com.grinningfrog.atlas.workspace.application

import java.net.URI

/** Pure policy shared by interception, navigation and message-origin checks. */
class WorkspacePresentationPolicy(val principal: WorkspacePrincipal, private val entry: String) {
    val origin = "https://ws-${WorkspaceApplicationContract.hash(listOf(principal.realmId, principal.workspaceId, principal.revisionId, principal.instanceId, principal.mode.name).joinToString("/")).take(40)}.atlas.invalid"
    val entryUrl = "$origin/$entry"
    companion object {
        const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; media-src 'self' blob:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
    fun originMatches(source: String): Boolean = source == origin || source == "$origin/"
    fun resource(url: String, method: String): String? = runCatching {
        if (method != "GET") return null
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawAuthority != URI(origin).rawAuthority || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null) return null
        val raw = uri.rawPath ?: return null
        if (!raw.startsWith('/') || raw.contains('%') || raw.contains('\\')) return null
        val path = raw.removePrefix("/")
        if (path == "atlas/host.js") return path
        path.takeIf { WorkspaceApplicationContract.validPath(it) }
    }.getOrNull()
    fun navigation(url: String): Boolean = resource(url, "GET") == entry
    fun acceptsMessage(source: String, mainFrame: Boolean, current: Boolean): Boolean = mainFrame && current && originMatches(source)
}
