package com.bsoft.bhandbook.core.recognition

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * Regression: drawing while a recognition was running crashed the app. The ViewModel cancels the running job, the engine noticed and threw
 * RecognitionCancelled, which was an ordinary RuntimeException, so it escaped the coroutine as an uncaught exception. It must be a real
 * CancellationException, which coroutines treat as a normal, silent cancellation.
 */
class RecognitionCancelledTest {
    @Test fun isACoroutineCancellation_soItNeverCrashesTheApp() {
        val e: Throwable = RecognitionCancelled()
        assertTrue(e is CancellationException)
        assertTrue(e is kotlin.coroutines.cancellation.CancellationException)
    }
    @Test fun keepsAMeaningfulMessage() { assertEquals("recognition cancelled", RecognitionCancelled().message) }
    @Test fun isStillCaughtByItsOwnType_andNotByAGenericFailureHandlerThatRethrowsCancellations() {
        fun run(): String = try { throw RecognitionCancelled() } catch (c: CancellationException) { "cancelled" } catch (e: Exception) { "failed" }
        assertEquals("cancelled", run())
    }
    @Test fun doesNotCostAStackTrace_becauseItIsThrownOnEveryCancelledRecognition() { assertEquals(0, RecognitionCancelled().stackTrace.size) }
}
