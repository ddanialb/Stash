package com.stash.core.media.automix

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoMixTrackAnalyzerSourceTest {

    private val streamAllowed = MutableStateFlow(true)
    private val resolver: StreamSourceRegistry = mockk()
    private val trackDao: TrackDao = mockk()
    private val urlCache = StreamUrlCache()
    private lateinit var analyzer: AutoMixTrackAnalyzer

    @Before
    fun setUp() {
        val streamingPreference = mockk<StreamingPreference> {
            every { enabled } returns streamAllowed
        }
        analyzer = AutoMixTrackAnalyzer(
            context = ApplicationProvider.getApplicationContext(),
            streamUrlCache = urlCache,
            streamResolver = resolver,
            trackDao = trackDao,
            streamingPreference = streamingPreference,
        )
    }

    private fun placeholder(trackId: Long = 42L): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId.toString())
            .setUri("$STASH_RESOLVE_SCHEME://track/$trackId")
            .build()

    private fun directHttps(trackId: Long = 42L): MediaItem =
        MediaItem.Builder()
            .setMediaId(trackId.toString())
            .setUri("https://cdn.example/song.flac")
            .build()

    private fun cachedRemote(isHls: Boolean = false) = StreamUrl(
        url = if (isHls) "https://cdn.example/cached.m3u8" else "https://cdn.example/cached.flac",
        expiresAtMs = System.currentTimeMillis() + 60 * 60_000L,
        origin = "test",
        isHls = isHls,
    )

    private fun audioFile(bytes: Int): File =
        File.createTempFile("automix-source-", ".flac").also { file ->
            file.writeBytes(ByteArray(bytes))
            file.deleteOnExit()
        }

    private fun downloadedEntity(file: File?): TrackEntity = TrackEntity(
        id = 42L,
        title = "Song",
        artist = "Artist",
        filePath = file?.absolutePath,
        isDownloaded = file != null,
    )

    @Test
    fun `usable downloaded file beats a cached stream URL`() = runBlocking {
        val file = audioFile(StashConstants.MIN_PLAYABLE_LOCAL_BYTES.toInt() + 1)
        coEvery { trackDao.getById(42L) } returns downloadedEntity(file)
        urlCache.put(42L, cachedRemote())
        streamAllowed.value = false // Local analysis remains allowed in Download mode.

        val resolved = analyzer.resolveAnalysisUri(placeholder())

        assertThat(resolved).isEqualTo(Uri.parse("file://${file.absolutePath}"))
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `a downloaded row with only an error body falls through to the cached stream`() = runBlocking {
        val file = audioFile(128)
        coEvery { trackDao.getById(42L) } returns downloadedEntity(file)
        urlCache.put(42L, cachedRemote())
        streamAllowed.value = true

        val resolved = analyzer.resolveAnalysisUri(placeholder())

        assertThat(resolved.toString()).isEqualTo("https://cdn.example/cached.flac")
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `Download mode refuses a cached remote URL when there is no usable download`() = runBlocking {
        coEvery { trackDao.getById(42L) } returns downloadedEntity(null)
        urlCache.put(42L, cachedRemote())
        streamAllowed.value = false

        assertThat(analyzer.resolveAnalysisUri(placeholder())).isNull()
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `an HLS playback URL is not used or replaced for analysis`() = runBlocking {
        coEvery { trackDao.getById(42L) } returns downloadedEntity(null)
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns cachedRemote()
        urlCache.put(42L, cachedRemote(isHls = true))
        streamAllowed.value = true

        assertThat(analyzer.resolveAnalysisUri(placeholder())).isNull()
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `direct stream item uses its usable download before the remote URL`() = runBlocking {
        val file = audioFile(StashConstants.MIN_PLAYABLE_LOCAL_BYTES.toInt() + 1)
        coEvery { trackDao.getById(42L) } returns downloadedEntity(file)
        streamAllowed.value = false

        assertThat(analyzer.resolveAnalysisUri(directHttps()))
            .isEqualTo(Uri.parse("file://${file.absolutePath}"))
    }

    @Test
    fun `direct stream item is refused in Download mode without a usable download`() = runBlocking {
        coEvery { trackDao.getById(42L) } returns downloadedEntity(null)
        streamAllowed.value = false

        assertThat(analyzer.resolveAnalysisUri(directHttps())).isNull()
    }

    @Test
    fun `switching to Download mode cancels a running remote resolve`() = runBlocking {
        coEvery { trackDao.getById(42L) } returns downloadedEntity(null)
        coEvery { resolver.resolve(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        streamAllowed.value = true

        val resolving = async { analyzer.resolveAnalysisUri(placeholder()) }
        delay(250)
        coVerify(exactly = 1) { resolver.resolve(any(), any(), any(), any()) }

        streamAllowed.value = false
        val failure = try {
            withTimeout(2_000) { resolving.await() }
            null
        } catch (e: CancellationException) {
            e
        }

        assertThat(failure).isNotNull()
        assertThat(resolving.isCancelled).isTrue()
    }
}
