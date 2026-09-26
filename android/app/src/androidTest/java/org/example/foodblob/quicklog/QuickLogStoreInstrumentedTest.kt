package org.example.foodblob.quicklog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.storage.FoodBlobDatabase
import org.example.foodblob.storage.FoodStore
import org.example.foodblob.storage.IdempotentMutationResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickLogStoreInstrumentedTest {
    private lateinit var database: FoodBlobDatabase
    private lateinit var store: FoodStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FoodBlobDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = FoodStore(
            database = database,
            clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
        )
        runBlocking { store.ensureInitialized() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedQuickLogExecutionIsDurableAndIdempotentAcrossReconciliation() = runBlocking {
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000903")

        assertEquals(
            IdempotentMutationResult.COMMITTED,
            store.appendQuickLogAction(FoodColor.GREEN, eventId),
        )
        assertEquals(
            IdempotentMutationResult.ALREADY_COMMITTED,
            store.appendQuickLogAction(FoodColor.GREEN, eventId),
        )
        assertEquals(FoodCounts(green = 1), store.effectiveSnapshot().counts("2026-08-11"))
        assertEquals(0, store.reconcilePending())
        assertEquals(
            IdempotentMutationResult.ALREADY_COMMITTED,
            store.appendQuickLogAction(FoodColor.GREEN, eventId),
        )
        assertEquals(FoodCounts(green = 1), store.effectiveSnapshot().counts("2026-08-11"))
    }

    @Test
    fun postCommitRefreshFailureCannotTurnADurableQuickLogIntoAFalseError() = runBlocking {
        val notifyingStore = FoodStore(
            database = database,
            clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneId.of("UTC")),
            zoneProvider = { ZoneId.of("UTC") },
            afterCommit = { error("synthetic refresh failure after durable commit") },
        )

        assertEquals(
            IdempotentMutationResult.COMMITTED,
            notifyingStore.appendQuickLogAction(
                FoodColor.YELLOW,
                UUID.fromString("00000000-0000-0000-0000-000000000904"),
            ),
        )
        assertEquals(FoodCounts(yellow = 1), notifyingStore.effectiveSnapshot().counts("2026-08-11"))
    }
}
