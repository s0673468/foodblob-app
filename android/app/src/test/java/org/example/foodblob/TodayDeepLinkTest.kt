package org.example.foodblob

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayDeepLinkTest {
    @Test
    fun acceptsOnlyTheExactTodayAuthority() {
        assertTrue(isTodayDeepLink("foodblob://today"))
        assertTrue(isTodayDeepLink("foodblob:///today"))

        listOf(
            null,
            "foodblob://today/",
            "foodblob://today/extra",
            "foodblob:///today/extra",
            "foodblob://today?source=widget",
            "foodblob://today#fragment",
            "foodblob://other/today",
            "https://today",
            "FOODBLOB://today",
        ).forEach { assertFalse(it.orEmpty(), isTodayDeepLink(it)) }
    }
}
