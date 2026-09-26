package org.example.foodblob.ui

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodBlobExportWriterTest {
    @Test
    fun `export opens writes and closes on the supplied io dispatcher`() {
        val output = CloseTrackingOutputStream()
        var openingThread = ""
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "foodblob-export-io")
        }.asCoroutineDispatcher()

        try {
            runBlocking {
                writeExportDocument(
                    json = "{\"green\":1}",
                    dispatcher = dispatcher,
                ) {
                    openingThread = Thread.currentThread().name
                    output
                }
            }
        } finally {
            dispatcher.close()
        }

        assertTrue(openingThread.startsWith("foodblob-export-io"))
        assertEquals("{\"green\":1}", output.toString(Charsets.UTF_8.name()))
        assertTrue(output.wasClosed)
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}
