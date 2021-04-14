package com.urbanairship.chat.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urbanairship.TestAirshipRuntimeConfig
import com.urbanairship.TestApplication
import com.urbanairship.TestRequest
import com.urbanairship.UAirship
import com.urbanairship.http.Request
import com.urbanairship.http.RequestException
import com.urbanairship.http.RequestFactory
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatApiClientTest {

    private lateinit var testRequest: TestRequest
    private lateinit var runtimeConfig: TestAirshipRuntimeConfig
    private lateinit var requestFactory: RequestFactory
    private lateinit var client: ChatApiClient

    @Before
    fun setUp() {
        testRequest = TestRequest()
        requestFactory = object : RequestFactory() {
            override fun createRequest(): Request {
                return testRequest
            }
        }
        runtimeConfig = TestAirshipRuntimeConfig.newTestConfig()

        client = ChatApiClient(runtimeConfig, requestFactory)
    }

    @Test
    fun testCreateUvp() {
        testRequest.responseStatus = 200
        testRequest.responseBody = """
            {
                "uvp": "neat"
            }
        """

        val uvp = client.fetchUvp("some-channel")
        Assert.assertEquals("neat", uvp)
        Assert.assertEquals("GET", testRequest.requestMethod)

        val expectedUrl = "https://ny4uaaegbg.execute-api.us-west-1.amazonaws.com/Prod/api/UVP?appKey=appKey&channelId=some-channel&platform=Android"
        Assert.assertEquals(expectedUrl, testRequest.url.toString())
    }

    @Test
    fun testCreateUvpAmazon() {
        TestApplication.getApplication().setPlatform(UAirship.AMAZON_PLATFORM)

        testRequest.responseStatus = 200
        testRequest.responseBody = """
            {
                "uvp": "neat"
            }
        """

        val uvp = client.fetchUvp("some-channel")
        Assert.assertEquals("neat", uvp)
        Assert.assertEquals("GET", testRequest.requestMethod)

        val expectedUrl = "https://ny4uaaegbg.execute-api.us-west-1.amazonaws.com/Prod/api/UVP?appKey=appKey&channelId=some-channel&platform=Amazon"
        Assert.assertEquals(expectedUrl, testRequest.url.toString())
    }

    @Test(expected = RequestException::class)
    fun testCreateUvpFails() {
        testRequest.responseStatus = 400
        val uvp = client.fetchUvp("some-channel")
    }

    @Test(expected = RequestException::class)
    fun testInvalidResponseBody() {
        testRequest.responseStatus = 200
        testRequest.responseBody = """
            {
                "no-uvp": "neat"
            }
        """

        testRequest.responseStatus = 400
        client.fetchUvp("some-channel")
    }
}
