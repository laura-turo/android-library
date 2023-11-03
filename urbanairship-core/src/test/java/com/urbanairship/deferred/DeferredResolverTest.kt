package com.urbanairship.deferred

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urbanairship.TestRequestSession
import com.urbanairship.UAirship
import com.urbanairship.audience.AudienceOverridesProvider
import com.urbanairship.config.AirshipRuntimeConfig
import com.urbanairship.json.JsonValue
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class DeferredResolverTest {
    public lateinit var resolver: DeferredResolver
    internal val requestSession = TestRequestSession()

    @Before
    public fun setup() {
        val config: AirshipRuntimeConfig = mockk()
        every { config.requestSession } returns requestSession
        every { config.platform } returns UAirship.ANDROID_PLATFORM

        resolver = DeferredResolver(config, AudienceOverridesProvider())
    }

    @Test
    public fun testSuccess(): TestResult = runTest {
        val expected = JsonValue.wrap("body")
        requestSession.addResponse(
            statusCode = 200,
            body = expected.toString(),
            headers = emptyMap()
        )

        val result = resolver.resolve(makeRequest()) { it } as? DeferredResult.Success<JsonValue>
        assertNotNull(result)
        assertEquals(expected, result?.result)
    }

    @Test
    public fun testSuccessNoValue(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 200
        )

        val result = resolver.resolve(makeRequest()) { it }
        assertTrue(result is DeferredResult.RetriableError)
    }

    @Test
    public fun testSuccessFailedToParse(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 200
        )

        val result = resolver.resolve(makeRequest()) { throw Exception() }
        assertTrue(result is DeferredResult.RetriableError)
    }

    @Test
    public fun testNotFound(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 404
        )

        val result = resolver.resolve(makeRequest()) { throw Exception() }
        assertTrue(result is DeferredResult.NotFound)
    }

    @Test
    public fun testOutdated(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 409
        )

        val result = resolver.resolve(makeRequest()) { throw Exception() }
        assertTrue(result is DeferredResult.OutOfDate)
    }

    @Test
    public fun testTooManyRequests(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 429,
            headers = mapOf(
                "Location" to "https://redirect.co",
                "Retry-After" to "123"
            )
        )

        val originalRequest = makeRequest()
        var result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(requestSession.lastRequest.url, originalRequest.uri)
        assertEquals(123L, result!!.retryAfter)

        result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(Uri.parse("https://redirect.co"), requestSession.lastRequest.url)
        assertNull(result!!.retryAfter) // we pop responses, so there we have no responses in the queue
    }

    @Test
    public fun testTemporalRedirectNoLocation(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 307,
        )

        val originalRequest = makeRequest()
        val result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(requestSession.lastRequest.url, originalRequest.uri)
        assertEquals(1, requestSession.requests.size)
    }

    @Test
    public fun testTemporalRedirectWaitsForDelay(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 307,
            headers = mapOf(
                "Location" to "https://redirect.co",
                "Retry-After" to "123"
            )
        )

        val originalRequest = makeRequest()
        val result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(requestSession.lastRequest.url, originalRequest.uri)
        assertEquals(1, requestSession.requests.size)
        assertEquals(123L, result!!.retryAfter)
    }

    @Test
    public fun testTemporalRedirectJump(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 307,
            headers = mapOf(
                "Location" to "https://redirect.co",
            )
        )

        val originalRequest = makeRequest()
        val result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(originalRequest.uri, requestSession.requests.first().url)
        assertEquals(requestSession.lastRequest.url, Uri.parse("https://redirect.co"))
        assertEquals(2, requestSession.requests.size)
    }

    @Test
    public fun testTemporalRedirectDoesOneJump(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 307,
            headers = mapOf(
                "Location" to "https://redirect.co",
            )
        )

        requestSession.addResponse(
            statusCode = 307,
            headers = mapOf(
                "Location" to "https://redirect-2.co",
            )
        )

        val originalRequest = makeRequest()
        val result = resolver.resolve(originalRequest) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
        assertEquals(originalRequest.uri, requestSession.requests.first().url)
        assertEquals(requestSession.lastRequest.url, Uri.parse("https://redirect.co"))
        assertEquals(2, requestSession.requests.size)
    }

    @Test
    public fun testInvalidResponseCode(): TestResult = runTest {
        requestSession.addResponse(
            statusCode = 502,
        )

        val result = resolver.resolve(makeRequest()) { it } as? DeferredResult.RetriableError
        assertNotNull(result)
    }

    private fun makeRequest(): DeferredRequest {
        val locale: Locale = mockk()
        every { locale.country } returns "country-le"
        every { locale.language } returns "language-le"

        return DeferredRequest(
            uri = Uri.parse("https://example.com"),
            channelID = "channel-id",
            locale = locale,
            notificationOptIn = true,
            appVersion = "1.2.3",
            sdkVersion = "4.3.2"
        )
    }
}
