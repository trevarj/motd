package io.github.trevarj.motd.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphTest {
    private val chatRouteName = requireNotNull(ChatRoute::class.qualifiedName)

    @Test
    fun `chat destination patterns use the drawer style transition`() {
        assertTrue(isChatRoutePattern(chatRouteName))
        assertTrue(isChatRoutePattern("$chatRouteName/{bufferId}"))
        assertTrue(isChatRoutePattern("$chatRouteName?bufferId={bufferId}"))
    }

    @Test
    fun `other and missing destinations retain the shared axis transition`() {
        assertFalse(isChatRoutePattern(ChatListRoute::class.qualifiedName))
        assertFalse(isChatRoutePattern("${ChatRoute::class.qualifiedName}Extra/{bufferId}"))
        assertFalse(isChatRoutePattern(null))
    }
}
