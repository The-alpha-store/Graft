package dev.graft.app.git

/**
 * Turns the raw exceptions JGit throws into something a person can act on, and decides which failures
 * are worth remembering.
 *
 * JGit's messages are written for developers: a dropped connection surfaces as
 * `https://github.com/you/repo.git: cannot open git-upload-pack`, which says nothing about *why*. The
 * real reason is usually a few `cause`s down (an `UnknownHostException`, a `SocketTimeoutException`...),
 * so classification walks the whole cause chain, including exception class names.
 *
 * Two kinds of failure matter differently:
 *
 * - **Transient** ([isTransient]): no connection, interrupted connection, timeout, server hiccup. Retrying
 *   later works. These must never be stored as a repo's lasting "error" - a sync that ran while you were in
 *   a tunnel would otherwise paint every repo red until the next success.
 * - **Actionable**: bad credentials, a rejected push. These stay visible because the person has to do
 *   something about them.
 */
object GitErrors {
    enum class Kind { OFFLINE, CONNECTION, TIMEOUT, SERVER, AUTH, REJECTED, OTHER }

    const val OFFLINE_MESSAGE =
        "You're offline, or the server can't be reached. Check your connection and try again."
    const val CONNECTION_MESSAGE =
        "The connection to the remote was interrupted. Try again - if it keeps happening, check your network or VPN."
    const val TIMEOUT_MESSAGE = "The server took too long to respond. Try again in a moment."
    const val SERVER_MESSAGE = "The remote server is having trouble right now. Try again later."
    const val AUTH_MESSAGE =
        "Authentication failed. Check the credential for this repository (is the token expired, or missing access?)."
    const val REJECTED_MESSAGE =
        "Push rejected: the remote has changes you don't have yet. Pull first, then push again."

    private val FRIENDLY = mapOf(
        Kind.OFFLINE to OFFLINE_MESSAGE,
        Kind.CONNECTION to CONNECTION_MESSAGE,
        Kind.TIMEOUT to TIMEOUT_MESSAGE,
        Kind.SERVER to SERVER_MESSAGE,
        Kind.AUTH to AUTH_MESSAGE,
        Kind.REJECTED to REJECTED_MESSAGE,
    )

    /** Classifies by the full text of an exception chain (messages and class names). */
    fun classify(error: Throwable): Kind {
        val text = generateSequence(error) { it.cause }
            .take(8)
            .joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        return classifyText(text)
    }

    /** Classifies a plain message - also recognises this object's own friendly messages. */
    fun classifyText(raw: String?): Kind {
        val t = raw.orEmpty().lowercase()
        if (t.isBlank()) return Kind.OTHER
        return when {
            // Already-friendly messages round-trip to their own kind.
            t == OFFLINE_MESSAGE.lowercase() -> Kind.OFFLINE
            t == CONNECTION_MESSAGE.lowercase() -> Kind.CONNECTION
            t == TIMEOUT_MESSAGE.lowercase() -> Kind.TIMEOUT
            t == SERVER_MESSAGE.lowercase() -> Kind.SERVER
            t == AUTH_MESSAGE.lowercase() -> Kind.AUTH
            t == REJECTED_MESSAGE.lowercase() -> Kind.REJECTED

            "rejected_nonfastforward" in t || "non-fast-forward" in t || "rejected_remote_changed" in t ||
                "failed to push some refs" in t -> Kind.REJECTED

            "not authorized" in t || "authentication is required" in t || "authentication failed" in t ||
                "invalid credentials" in t || "bad credentials" in t || "no credentialsprovider" in t ||
                ("401" in t || "403" in t) && ("http" in t || "status" in t) -> Kind.AUTH

            "unknownhostexception" in t || "unable to resolve host" in t || "no address associated" in t ||
                "network is unreachable" in t || "enetunreach" in t || "no route to host" in t ||
                "connectexception" in t || "failed to connect" in t -> Kind.OFFLINE

            // Only as an exception type or a full phrase: a bare "timeout" would also match a branch
            // or file that happens to be called that.
            "timeoutexception" in t || "timed out" in t -> Kind.TIMEOUT

            "service unavailable" in t || "internal server error" in t || "bad gateway" in t ||
                ("502" in t || "503" in t || "500" in t) && "http" in t -> Kind.SERVER

            "cannot open git-upload-pack" in t || "cannot open git-receive-pack" in t ||
                "connection abort" in t || "connection reset" in t || "broken pipe" in t ||
                "unexpected end of stream" in t || "stream was reset" in t || "connection closed" in t ||
                "eofexception" in t || "sslexception" in t || "sslhandshake" in t || "socketexception" in t -> Kind.CONNECTION

            else -> Kind.OTHER
        }
    }

    /** The message to show for [error]: a clear sentence for known problems, the original text otherwise. */
    fun friendly(error: Throwable): String {
        FRIENDLY[classify(error)]?.let { return it }
        return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    fun friendlyText(raw: String?): String = FRIENDLY[classifyText(raw)] ?: raw.orEmpty()

    fun isTransient(kind: Kind): Boolean =
        kind == Kind.OFFLINE || kind == Kind.CONNECTION || kind == Kind.TIMEOUT || kind == Kind.SERVER

    /** True for failures that a retry will probably fix - never worth recording as a repo's error. */
    fun isTransientMessage(raw: String?): Boolean = isTransient(classifyText(raw))
}
