package dev.graft.app.git

/**
 * The web page for a repo's clone URL, or null when it isn't something a browser can show.
 *
 * Handles the shapes people actually have: `https://github.com/o/r.git`, `https://user:token@host/o/r`
 * (credentials stripped - never put a token into an Intent or the clipboard by accident), and scp-style
 * `git@github.com:o/r.git`. Local paths and unknown schemes return null.
 */
fun browserUrlFor(cloneUrl: String): String? {
    val url = cloneUrl.trim()
    if (url.isEmpty()) return null

    val scp = Regex("""^[\w.-]+@([\w.-]+):(.+?)(?:\.git)?/?$""").find(url)
    if (scp != null) {
        val (host, path) = scp.destructured
        return "https://$host/$path"
    }

    val http = Regex("""^(https?)://(?:[^/@]+@)?([^/]+)/(.+?)(?:\.git)?/?$""").find(url) ?: return null
    val (scheme, host, path) = http.destructured
    return "$scheme://$host/$path"
}
