package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.friends.Friend
import com.bgdeveloper.ridelirium.friends.Invite
import com.bgdeveloper.ridelirium.friends.InviteLink
import com.bgdeveloper.ridelirium.friends.initialsOf
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
            "ridelirium://join/ABCD2345?id=147437F1-A106-4793-B264-DEDAD024E171&name=Alex%20Rider&initials=AR",
            InviteLink.url("ABCD2345", alex),
        )
    }

    @Test
    fun parsesIosLink() {
        val (code, inviter) = InviteLink.parse("ridelirium://join/ABCD2345?id=abc-1&name=Bo%20Test&initials=BT")!!
        assertEquals("ABCD2345", code)
        assertEquals(Friend("ABC-1", "Bo Test", "BT"), inviter)
    }

    @Test
    fun typedCodeNeedsEightLettersOrDigits() {
        assertEquals("ABCD2345" to null, InviteLink.parse("abcd-2345"))
        assertNull(InviteLink.parse("ABC"))
        assertNull(InviteLink.parse("ridelirium://join/ABC"))
    }

    @Test
    fun linkWithoutInviterStillJoins() {
        assertEquals("ABCD2345" to null, InviteLink.parse("ridelirium://join/ABCD2345"))
    }

    @Test
    fun webLinkRoundTrips() {
        val url = InviteLink.url("ABCD2345", alex, "ridelirium.com")
        assertEquals("https://ridelirium.com/join/ABCD2345", url)
        assertEquals("ABCD2345" to null, InviteLink.parse(url, "ridelirium.com"))
        assertEquals("ABCD2345" to null, InviteLink.parse("HTTPS://ridelirium.com/join/abcd2345?x=1", "ridelirium.com"))
        assertNull(InviteLink.parse("https://evil.example/join/ABCD2345", "ridelirium.com"))
    }

    @Test
    fun linksOnTheOldApiHostAreNotInvites() {
        assertNull(InviteLink.parse("https://ridersapi.sportco.net/join/ABCD2345", "ridelirium.com"))
        assertEquals(false, InviteLink.isInvite("https", "ridersapi.sportco.net", "/join/ABCD2345", "ridelirium.com"))
    }

    @Test
    fun recognisesInviteLinks() {
        assertEquals(true, InviteLink.isInvite("https", "ridelirium.com", "/join/ABCD2345", "ridelirium.com"))
        assertEquals(false, InviteLink.isInvite("https", "ridelirium.com", "/other", "ridelirium.com"))
        assertEquals(false, InviteLink.isInvite("https", "evil.example", "/join/ABCD2345", "ridelirium.com"))
        assertEquals(true, InviteLink.isInvite("ridelirium", "join", "/ABCD2345", null))
    }

    @Test
    fun invitesAreSharedOnTheWebsiteHost() {
        assertEquals("https://ridelirium.com/join/ABCD2345", Invite("ABCD2345", alex).url)
    }

    @Test
    fun initials() {
        assertEquals("AR", initialsOf("alex rider extra"))
        assertEquals("B", initialsOf("bo"))
        assertEquals("ME", initialsOf("  "))
    }
}
