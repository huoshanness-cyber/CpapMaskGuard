package app.cpapmaskguard.capture

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            if (!cont.isActive) return@addListener
            try {
                cont.resume(get())
            } catch (error: ExecutionException) {
                cont.resumeWithException(error.cause ?: error)
            } catch (error: Throwable) {
                cont.resumeWithException(error)
            }
        }, ContextCompat.getMainExecutor(context))
        cont.invokeOnCancellation { cancel(true) }
    }
