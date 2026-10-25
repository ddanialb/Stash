package com.stash.core.data.cache

import app.cash.turbine.test
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * #403: `YTMusicApiClient.getArtist` answers a browse that never came back
 * (offline, ISP/VPN block, InnerTube error) with a blank-name, shelf-less
 * profile instead of throwing. [ArtistCache] used to persist that
 * placeholder and serve it as Fresh for the 6-hour TTL, so an artist first
 * opened without a connection stayed an empty page with no Retry long
 * after the connection returned. Same failure [AlbumCache] guards against
 * for `getAlbum` (#481); these tests pin the artist version:
 *
 *  1. A cold no-answer is a failed load: thrown, nothing persisted, so the
 *     next get asks the network again.
 *  2. A no-answer stale refresh keeps the good cached profile and does
 *     not re-stamp its row.
 *  3. A no-answer row persisted by an older version is ignored (self-heal)
 *     instead of being served as Fresh.
 *  4. `forceRefresh` bypasses the TTL, so an explicit Retry refetches.
 */
class ArtistCacheNoAnswerTest {

    /** The placeholder getArtist returns when the browse never answered. */
    private fun noAnswer(id: String) = ArtistProfile(
        id = id,
        name = "",
        avatarUrl = null,
        subscribersText = null,
        popular = emptyList(),
        albums = emptyList(),
        singles = emptyList(),
        related = emptyList(),
    )

    private fun mkProfile(id: String, name: String) = ArtistProfile(
        id = id,
        name = name,
        avatarUrl = null,
        subscribersText = null,
        popular = emptyList(),
        albums = emptyList(),
        singles = emptyList(),
        related = emptyList(),
    )

    @Test
    fun `a blank artist with no shelves is a failed load and is not cached`() = runTest {
        val dao = InMemoryDao()
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(noAnswer("UC1"), mkProfile("UC1", "A"))
        val cache = ArtistCache(dao, api, now = { 1_000L })

        cache.get("UC1").test {
            val failure = awaitError()
            assertTrue("got $failure", failure is IOException)
        }
        // Nothing was kept: no disk row, no memory entry.
        assertNull(dao.get("UC1"))
        assertFalse(cache.memoryContains("UC1"))

        // So the next open asks again — and this time the (restored)
        // connection answers.
        cache.get("UC1").test {
            assertTrue(awaitItem() is CachedProfile.Partial)
            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertEquals("A", (fresh as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
        verify(api, times(2)).getArtist(eq("UC1"))
    }

    @Test
    fun `stale refresh with no answer keeps the cached profile and does not restamp its row`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Cached", fetchedAt = 0L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(noAnswer("UC1"))
        val ttl7h = 7 * 60 * 60 * 1000L
        val cache = ArtistCache(dao, api, now = { ttl7h })

        cache.get("UC1").test {
            val stale = awaitItem() as CachedProfile.Stale
            assertEquals("Cached", stale.profile.name)
            assertFalse(stale.refreshFailed)
            val after = awaitItem() as CachedProfile.Stale
            assertTrue(after.refreshFailed)
            assertEquals("Cached", after.profile.name)
            cancelAndIgnoreRemainingEvents()
        }
        // The good row survived untouched: same fetchedAt, so the next open
        // retries the refresh instead of serving the failure as Fresh.
        val row = dao.get("UC1")
        assertEquals(0L, row?.fetchedAt)
        assertTrue(row!!.json.contains("Cached"))
    }

    @Test
    fun `a persisted no-answer row is ignored and refetched even within TTL`() = runTest {
        val dao = InMemoryDao().apply {
            // Poisoned by an older version: blank name, fetched 1 min ago.
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "", fetchedAt = 1_000L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "Healed"))
        val cache = ArtistCache(dao, api, now = { 1_000L + 60_000L })

        cache.get("UC1").test {
            assertTrue(awaitItem() is CachedProfile.Partial)
            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertEquals("Healed", (fresh as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
        verify(api, times(1)).getArtist(eq("UC1"))
        assertTrue(dao.get("UC1")!!.json.contains("Healed"))
    }

    @Test
    fun `forceRefresh refetches a within-TTL hit`() = runTest {
        val dao = InMemoryDao().apply {
            upsert(ArtistCacheEntityFixtures.serialized("UC1", "Old", fetchedAt = 1_000L))
        }
        val api = mock<YTMusicApiClient>()
        whenever(api.getArtist(eq("UC1"))).thenReturn(mkProfile("UC1", "New"))
        // 1 min later — well inside the 6h TTL, so only the force flag can
        // explain a network call.
        val cache = ArtistCache(dao, api, now = { 1_000L + 60_000L })

        cache.get("UC1", forceRefresh = true).test {
            val stale = awaitItem() as CachedProfile.Stale
            assertEquals("Old", stale.profile.name)
            val fresh = awaitItem()
            assertTrue(fresh is CachedProfile.Fresh)
            assertEquals("New", (fresh as CachedProfile.Fresh).profile.name)
            cancelAndIgnoreRemainingEvents()
        }
        verify(api, times(1)).getArtist(eq("UC1"))
    }
}
