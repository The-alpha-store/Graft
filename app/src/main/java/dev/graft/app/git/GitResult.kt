package dev.graft.app.git

/**
 * Wraps every git operation's outcome so failures surface as a normal
 * value instead of an uncaught exception reaching the UI layer.
 */
sealed class GitResult<out T> {
    data class Success<T>(val data: T) : GitResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : GitResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    inline fun onSuccess(block: (T) -> Unit): GitResult<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onError(block: (String) -> Unit): GitResult<T> {
        if (this is Error) block(message)
        return this
    }
}

/** Runs [block], catching any exception and wrapping the outcome as a GitResult. */
inline fun <T> gitTry(block: () -> T): GitResult<T> {
    return try {
        GitResult.Success(block())
    } catch (e: Exception) {
        GitResult.Error(e.message ?: e.javaClass.simpleName, e)
    }
}
