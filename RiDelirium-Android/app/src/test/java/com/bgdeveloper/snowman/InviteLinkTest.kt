package com.bgdeveloper.snowman

import com.bgdeveloper.snowman.friends.Friend
import com.bgdeveloper.snowman.friends.InviteLink
import com.bgdeveloper.snowman.friends.initialsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val alex = Friend("147437F1-A106-4793-B264-DEDAD024E171", "Alex Rider", "AR")

    @Test
    fun linkRoundTrips() {
        val url = InviteLink.url("ABCD2345", alex)
        val (code, inviter) = InviteLink.parse(url)!!
        assertEquals("ABCD2345", code)
        assertEquals(alex, inviter)
    }

    @Test
    fun spacesAreEncodedLikeIos() {
        assertEquals(
            "snowboarder://join/ABCD2345?id=147437F1-A106-4793-B264-DEDAD024E171&name=Alex%20Rider&initials=AR",
            InviteLink.url("ABCD2345", alex),
        )
    }

    @Test
    fun parsesIosLink() {
        val (code, inviter) = InviteLink.parse("snowboarder://join/ABCD2345?id=abc-1&name=Bo%20Test&initials=BT")!!
        assertEquals("ABCD2345", code)
        assertEquals(Friend("ABC-1", "Bo Test", "BT"), inviter)
    }

    @Test
    fun typedCodeNeedsEightLettersOrDigits() {
        assertEquals("ABCD2345" to null, InviteLink.parse("abcd-2345"))
        assertNull(InviteLink.parse("ABC"))
        assertNull(InviteLink.parse("snowboarder://join/ABC"))
    }

    @Test
    fun linkWithoutInviterStillJoins() {
        assertEquals("ABCD2345" to null, InviteLink.parse("snowboarder://join/ABCD2345"))
    }

    @Test
    fun webLinkRoundTrips() {
        val url = InviteLink.url("ABCD2345", alex, "ridersapi.sportco.net")
        assertEquals("https://ridersapi.sportco.net/join/ABCD2345", url)
        assertEquals("ABCD2345" to null, InviteLink.parse(url, "ridersapi.sportco.net"))
        assertEquals("ABCD2345" to null, InviteLink.parse("HTTPS://ridersapi.sportco.net/join/abcd2345?x=1", "ridersapi.sportco.net"))
        assertNull(InviteLink.parse("https://evil.example/join/ABCD2345", "ridersapi.sportco.net"))
    }

    @Test
    fun recognisesInviteLinks() {
        assertEquals(true, InviteLink.isInvite("https", "ridersapi.sportco.net", "/join/ABCD2345", "ridersapi.sportco.net"))
        assertEquals(false, InviteLink.isInvite("https", "ridersapi.sportco.net", "/other", "ridersapi.sportco.net"))
        assertEquals(false, InviteLink.isInvite("https", "evil.example", "/join/ABCD2345", "ridersapi.sportco.net"))
        assertEquals(true, InviteLink.isInvite("snowboarder", "join", "/ABCD2345", null))
    }

    @Test
    fun hostIsTakenFromTheApiAddress() {
        assertEquals("ridersapi.sportco.net", InviteLink.hostOf("https://ridersapi.sportco.net/v1"))
        assertNull(InviteLink.hostOf("http://10.10.10.18/riderapi/v1"))
        assertNull(InviteLink.hostOf(""))
    }

    @Test
    fun initials() {
        assertEquals("AR", initialsOf("alex rider extra"))
        assertEquals("B", initialsOf("bo"))
        assertEquals("ME", initialsOf("  "))
    }
}
