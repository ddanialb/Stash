package com.stash.core.media.automix

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoMixAnalysisRunnerTest {

    @Test
    fun `at most two analyses run at once`() = runTest {
        val runner = AutoMixAnalysisRunner<String>(maxConcurrent = 2)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val jobs = (1..5).map { index ->
            async {
                runner.run("track-$index") {
                    val now = active.incrementAndGet()
                    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    try {
                        delay(100)
                        "analysis-$index"
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        advanceUntilIdle()

        assertThat(jobs.awaitAll()).containsExactly(
            "analysis-1", "analysis-2", "analysis-3", "analysis-4", "analysis-5",
        ).inOrder()
        assertThat(peak.get()).isAtMost(2)
        assertThat(active.get()).isEqualTo(0)
    }

    @Test
    fun `rapid skips leave no analysis running and do not grow the decoder count`() = runTest {
        val runner = AutoMixAnalysisRunner<String>(maxConcurrent = 2)
        val started = AtomicInteger(0)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val jobs = (1..10).map { index ->
            async {
                runner.run("rapid-skip-$index") {
                    started.incrementAndGet()
                    val now = active.incrementAndGet()
                    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    try {
                        awaitCancellation()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
        runCurrent()

        // This is the caller-side equivalent of skipping rapidly and then
        // switching AutoMix off: every pair's owner is cancelled, including
        // requests still waiting for one of the two decoder slots. Cancel all
        // owners before joining any of them, as the service does when it drops
        // the whole plan, so a freed slot cannot hand work to an owner whose
        // cancellation has not been issued yet.
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }

        assertThat(jobs.all { it.isCancelled }).isTrue()
        assertThat(started.get()).isAtMost(2)
        assertThat(peak.get()).isAtMost(2)
        assertThat(active.get()).isEqualTo(0)
    }

    @Test
    fun `cancelling a caller cancels its work and does not cache it`() = runTest {
        val runner = AutoMixAnalysisRunner<String>(maxConcurrent = 2)
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val calls = AtomicInteger(0)

        val job = async {
            runner.run("same-track") {
                calls.incrementAndGet()
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
        }
        runCurrent()
        assertThat(started.isCompleted).isTrue()

        job.cancelAndJoin()

        assertThat(cancelled.get()).isTrue()
        assertThat(job.isCancelled).isTrue()

        val fresh = runner.run("same-track") {
            calls.incrementAndGet()
            "fresh"
        }
        assertThat(fresh).isEqualTo("fresh")
        assertThat(calls.get()).isEqualTo(2)

        // The completed value is cached, so asking a third time does no work.
        val cached = runner.run("same-track") {
            calls.incrementAndGet()
            "should-not-run"
        }
        assertThat(cached).isEqualTo("fresh")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `switching to Download mode cancels running remote work`() = runTest {
        val streamAllowed = MutableStateFlow(true)
        val policy = AutoMixRemotePolicy(streamAllowed)
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)

        val job = async {
            policy.withCancellation {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
        }
        runCurrent()
        assertThat(started.isCompleted).isTrue()

        streamAllowed.value = false
        runCurrent()

        assertThat(cancelled.get()).isTrue()
        assertThat(job.isCancelled).isTrue()
        val failure = try {
            job.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertThat(failure).isNotNull()
    }

    @Test
    fun `remote work is refused when Download mode is already on`() = runTest {
        val policy = AutoMixRemotePolicy(MutableStateFlow(false))
        val calls = AtomicInteger(0)

        val failure = try {
            policy.withCancellation {
                calls.incrementAndGet()
                "should-not-run"
            }
            null
        } catch (e: CancellationException) {
            e
        }

        assertThat(failure).isNotNull()
        assertThat(calls.get()).isEqualTo(0)
    }
}
