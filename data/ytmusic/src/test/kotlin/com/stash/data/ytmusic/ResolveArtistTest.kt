package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.ArtistPhotoResolution
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class ResolveArtistTest {
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun fakeInner(responseJson: String?): InnerTubeClient {
        val inner = mock<InnerTubeClient>()
        val parsed = responseJson?.let { Json.parseToJsonElement(it).jsonObject }
        runBlocking {
            whenever(inner.search(any(), any())).thenReturn(parsed)
            whenever(inner.searchWithStatus(any(), any())).thenReturn(
                InnerTubeClient.RequestOutcome(
                    body = parsed,
                    statusCode = if (parsed != null) 200 else InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR,
                ),
            )
        }
        return inner
    }

    private fun fakeClient(responseJson: String?): YTMusicApiClient =
        YTMusicApiClient(fakeInner(responseJson))

    @Test fun `resolveArtist returns top artist id name and avatar`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        val result = client.resolveArtist("my bloody valentine")
        assertEquals("UCuoGeza7Dl9Ni_hmeLTPdkg", result?.id)
        assertEquals("My Bloody Valentine", result?.name)
        assertNotNull(result?.avatarUrl)
    }

    @Test fun `resolveArtist returns null when no artists shelf`() = runTest {
        val client = fakeClient("""{"contents":{}}""")
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null on null response`() = runTest {
        val client = fakeClient(null)
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null for blank name without hitting the network`() = runTest {
        // Stub with a VALID artists response so a null result can only come from
        // the blank-name guard — not from null-response handling. Also assert the
        // search is never issued, so deleting the guard fails this test.
        val inner = fakeInner(loadFixture("search_artists_filter.json"))
        val client = YTMusicApiClient(inner)
        assertNull(client.resolveArtist("   "))
        verifyBlocking(inner, never()) { search(any(), any()) }
    }

    // #666: the fixture shelf is [My Bloody Valentine, Kevin Shields]. The
    // resolver used to return the first entry whatever was searched, so a
    // tap on Kevin Shields (or on a Radiohead track) opened MBV's page.

    @Test fun `resolveArtist picks the artist that matches, not the first on the shelf`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        val result = client.resolveArtist("Kevin Shields")
        assertEquals("UCotherKevinShields", result?.id)
        assertEquals("Kevin Shields", result?.name)
    }

    @Test fun `resolveArtist returns null when nobody on the shelf matches`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        assertNull(client.resolveArtist("Radiohead"))
    }

    @Test fun `resolveArtist prefers the primary act of a joint credit`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        // Shelf order is MBV first; the credit names Kevin Shields first.
        val result = client.resolveArtist("Kevin Shields, My Bloody Valentine")
        assertEquals("Kevin Shields", result?.name)
    }

    @Test fun `resolveArtist matches a name with a comma whole before its parts`() = runTest {
        // The same shelf renamed [Peter, "Peter, Bjorn and John"]: split into credits (the name
        // isn't on the allowlist), the searched name names the first entry ("Peter") exactly; the
        // whole name is the second.
        val shelf = loadFixture("search_artists_filter.json")
            .replace("My Bloody Valentine", "Peter")
            .replace("Kevin Shields", "Peter, Bjorn and John")
        val result = fakeClient(shelf).resolveArtist("Peter, Bjorn and John")
        assertEquals("UCotherKevinShields", result?.id)
        assertEquals("Peter, Bjorn and John", result?.name)
    }

    @Test fun `resolveArtistPhoto resolves an avatar when an artist shelf exists`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        val result = client.resolveArtistPhoto("my bloody valentine")
        assertTrue(result is ArtistPhotoResolution.Resolved)
        assertNotNull((result as ArtistPhotoResolution.Resolved).avatarUrl)
    }

    @Test fun `resolveArtistPhoto is NoAvatar when the API answered without an artist`() = runTest {
        val client = fakeClient("""{"contents":{}}""")
        assertEquals(ArtistPhotoResolution.NoAvatar, client.resolveArtistPhoto("nobody"))
    }

    @Test fun `resolveArtistPhoto is NoAvatar when the shelf names only other artists`() = runTest {
        // Answered, but nobody matching: a genuine no-photo result, never
        // the unrelated top artist's photo stamped onto this artist.
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        assertEquals(ArtistPhotoResolution.NoAvatar, client.resolveArtistPhoto("Radiohead"))
    }

    @Test fun `resolveArtistPhoto is Failed when the API did not answer`() = runTest {
        val client = fakeClient(null)
        assertEquals(ArtistPhotoResolution.Failed, client.resolveArtistPhoto("nobody"))
    }

    @Test fun `resolveArtistPhoto is NoAvatar for a blank name without hitting the network`() = runTest {
        val inner = fakeInner(loadFixture("search_artists_filter.json"))
        val client = YTMusicApiClient(inner)
        assertEquals(ArtistPhotoResolution.NoAvatar, client.resolveArtistPhoto("   "))
        verifyBlocking(inner, never()) { searchWithStatus(any(), any()) }
    }
}
