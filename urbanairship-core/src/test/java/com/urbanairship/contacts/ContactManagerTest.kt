/* Copyright Airship and Contributors */

package com.urbanairship.contacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.urbanairship.PreferenceDataStore
import com.urbanairship.TestClock
import com.urbanairship.audience.AudienceOverrides
import com.urbanairship.audience.AudienceOverridesProvider
import com.urbanairship.channel.AirshipChannel
import com.urbanairship.channel.AttributeMutation
import com.urbanairship.channel.TagGroupsMutation
import com.urbanairship.http.RequestResult
import com.urbanairship.job.JobDispatcher
import com.urbanairship.json.JsonValue
import com.urbanairship.locale.LocaleManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
public class ContactManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockChannel = mockk<AirshipChannel>(relaxed = true) {
        every { id } returns "some channel id"
    }

    private val mockApiClient = mockk<ContactApiClient>()
    private val mockJobDispatcher = mockk<JobDispatcher>(relaxed = true)
    private val mockLocaleManager = mockk<LocaleManager>() {
        every { locale } returns Locale.ENGLISH
    }

    private val testClock = TestClock()
    private val audienceOverridesProvider = AudienceOverridesProvider(testClock)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferenceDataStore = PreferenceDataStore.inMemoryStore(context)

    private val contactManager = ContactManager(
        preferenceDataStore,
        mockChannel,
        mockJobDispatcher,
        mockApiClient,
        mockLocaleManager,
        audienceOverridesProvider,
        testClock,
        testDispatcher
    ).also {
        it.isEnabled = true
    }

    private val anonIdentityResult = ContactApiClient.IdentityResult(
        contactId = "anon contact",
        isAnonymous = true,
        channelAssociatedDateMs = testClock.currentTimeMillis,
        token = "some token",
        tokenExpiryDateMs = testClock.currentTimeMillis + 36000
    )

    private val nonAnonIdentifyResult = ContactApiClient.IdentityResult(
        contactId = "non anon contact",
        isAnonymous = false,
        channelAssociatedDateMs = testClock.currentTimeMillis,
        token = "some token",
        tokenExpiryDateMs = testClock.currentTimeMillis + 36000
    )

    @Before
    public fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    public fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    public fun testEnableEnqueuesJob(): TestResult = runTest {
        contactManager.isEnabled = false
        contactManager.addOperation(ContactOperation.Resolve)
        verify(exactly = 0) { mockJobDispatcher.dispatch(any()) }

        contactManager.isEnabled = true
        verify(exactly = 1) { mockJobDispatcher.dispatch(any()) }
    }

    @Test
    public fun testAddOperationEnqueuesJob(): TestResult = runTest {
        verify(exactly = 0) { mockJobDispatcher.dispatch(any()) }
        contactManager.addOperation(ContactOperation.Resolve)
        verify(exactly = 1) { mockJobDispatcher.dispatch(any()) }
    }

    @Test
    public fun testJobRateLimits(): TestResult = runTest {
        verify {
            mockJobDispatcher.setRateLimit(
                ContactManager.IDENTITY_RATE_LIMIT, 1, 5, TimeUnit.SECONDS
            )
        }
        verify {
            mockJobDispatcher.setRateLimit(
                ContactManager.UPDATE_RATE_LIMIT, 1, 500, TimeUnit.MILLISECONDS
            )
        }
    }

    @Test
    public fun testResolve(): TestResult = runTest {
        assertNull(contactManager.lastContactId)
        contactManager.addOperation(ContactOperation.Resolve)
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)

        contactManager.contactIdUpdates.test {
            assertEquals(anonIdentityResult.contactId, this.awaitItem()?.contactId)
        }
    }

    @Test
    public fun testResolveFailsClientError(): TestResult = runTest {
        assertNull(contactManager.lastContactId)
        contactManager.addOperation(ContactOperation.Resolve)
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 400, value = null, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertNull(contactManager.lastContactId)
    }

    @Test
    public fun testResolveFailsServerError(): TestResult = runTest {
        assertNull(contactManager.lastContactId)
        contactManager.addOperation(ContactOperation.Resolve)
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 500, value = null, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(false, result)
        assertNull(contactManager.lastContactId)
    }

    @Test
    public fun testResolveFailsException(): TestResult = runTest {
        assertNull(contactManager.lastContactId)
        contactManager.addOperation(ContactOperation.Resolve)
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            IllegalArgumentException("neat")
        )

        val result = contactManager.performNextOperation()
        assertEquals(false, result)
        assertNull(contactManager.lastContactId)
    }

    @Test
    public fun testIdentify(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Identify("some named user id"))

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.identify(
                "some channel id", anonIdentityResult.contactId, "some named user id"
            )
        } returns RequestResult(
            status = 200, value = nonAnonIdentifyResult, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertEquals(nonAnonIdentifyResult.contactId, contactManager.lastContactId)

        contactManager.contactIdUpdates.test {
            assertEquals(nonAnonIdentifyResult.contactId, this.awaitItem()?.contactId)
        }
    }

    @Test
    public fun testIdentifyClientError(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Identify("some named user id"))

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.identify(
                "some channel id", anonIdentityResult.contactId, "some named user id"
            )
        } returns RequestResult(
            status = 400, value = null, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testIdentifyServerError(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Identify("some named user id"))

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.identify(
                "some channel id", anonIdentityResult.contactId, "some named user id"
            )
        } returns RequestResult(
            status = 500, value = null, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(false, result)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testReset(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Reset)

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = nonAnonIdentifyResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.reset(
                "some channel id"
            )
        } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)

        contactManager.contactIdUpdates.test {
            assertEquals(anonIdentityResult.contactId, this.awaitItem()?.contactId)
        }
    }

    @Test
    public fun testResetServerError(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Reset)

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = nonAnonIdentifyResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.reset(
                "some channel id"
            )
        } returns RequestResult(
            status = 500, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(false, result)
        assertEquals(nonAnonIdentifyResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testResetClientError(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Reset)

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = nonAnonIdentifyResult, body = null, headers = emptyMap()
        )

        coEvery {
            mockApiClient.reset(
                "some channel id"
            )
        } returns RequestResult(
            status = 400, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)
        assertEquals(nonAnonIdentifyResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testAuthTokenNoContact(): TestResult = runTest {
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.fetchToken(anonIdentityResult.contactId)
        assertEquals(Result.success(anonIdentityResult.token), result)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)

        contactManager.contactIdUpdates.test {
            assertEquals(anonIdentityResult.contactId, this.awaitItem()?.contactId)
        }
    }

    @Test
    public fun testAuthTokenMismatchContactId(): TestResult = runTest {
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.fetchToken("some other contact id")
        assertTrue(result.isFailure)
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testExpireAuthToken(): TestResult = runTest {
        coEvery { mockApiClient.resolve(any(), any()) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        assertEquals(
            Result.success(anonIdentityResult.token),
            contactManager.fetchToken(anonIdentityResult.contactId)
        )
        assertEquals(
            Result.success(anonIdentityResult.token),
            contactManager.fetchToken(anonIdentityResult.contactId)
        )

        coVerify(exactly = 1) { mockApiClient.resolve(any(), any()) }

        contactManager.expireToken(anonIdentityResult.token)
        assertEquals(
            Result.success(anonIdentityResult.token),
            contactManager.fetchToken(anonIdentityResult.contactId)
        )
        coVerify(exactly = 2) { mockApiClient.resolve(any(), any()) }
    }

    @Test
    public fun testAuthTokenFailed(): TestResult = runTest {
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 400, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        val result = contactManager.fetchToken(anonIdentityResult.contactId)
        assertTrue(result.isFailure)
    }

    @Test
    public fun testGenerateDefaultContactId(): TestResult = runTest {
        assertNull(contactManager.lastContactId)
        contactManager.generateDefaultContactIdIfNotSet()
        assertNotNull(contactManager.lastContactId)

        contactManager.contactIdUpdates.test {
            assertNotNull(anonIdentityResult.contactId, this.awaitItem()?.contactId)
        }
    }

    @Test
    public fun testGenerateDefaultContactIdAlreadySet(): TestResult = runTest {
        contactManager.addOperation(ContactOperation.Resolve)
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )
        assertEquals(true, contactManager.performNextOperation())
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)

        contactManager.generateDefaultContactIdIfNotSet()
        assertEquals(anonIdentityResult.contactId, contactManager.lastContactId)
    }

    @Test
    public fun testContactUnstablePendingReset(): TestResult = runTest {
        contactManager.generateDefaultContactIdIfNotSet()
        contactManager.contactIdUpdates.test {
            assertEquals(true, awaitItem()?.isStable)
        }

        contactManager.addOperation(ContactOperation.Reset)
        contactManager.contactIdUpdates.test {
            assertEquals(false, awaitItem()?.isStable)
        }
    }

    @Test
    public fun testContactUnstablePendingIdentify(): TestResult = runTest {
        contactManager.generateDefaultContactIdIfNotSet()
        contactManager.contactIdUpdates.test {
            assertEquals(true, awaitItem()?.isStable)
        }

        contactManager.addOperation(ContactOperation.Identify("some named user"))
        contactManager.contactIdUpdates.test {
            assertEquals(false, awaitItem()?.isStable)
        }
    }

    @Test
    public fun testUpdate(): TestResult = runTest {
        val subscriptions = listOf(
            ScopedSubscriptionListMutation.newUnsubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            ), ScopedSubscriptionListMutation.newSubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            )
        )

        val tags = listOf(
            TagGroupsMutation.newAddTagsMutation("some group", setOf("some tag")),
            TagGroupsMutation.newRemoveTagsMutation("some group", setOf("some tag"))
        )

        val attributes = listOf(
            AttributeMutation.newRemoveAttributeMutation(
                "some attribute", testClock.currentTimeMillis
            ), AttributeMutation.newSetAttributeMutation(
                "some attribute", JsonValue.wrap("some value"), testClock.currentTimeMillis
            )
        )

        contactManager.addOperation(ContactOperation.Update(tags = tags))
        contactManager.addOperation(ContactOperation.Update(attributes = attributes))
        contactManager.addOperation(ContactOperation.Update(subscriptions = subscriptions))

        // Resolve is called first
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()

        )
        coEvery {
            mockApiClient.update(
                anonIdentityResult.contactId,
                TagGroupsMutation.collapseMutations(tags),
                AttributeMutation.collapseMutations(attributes),
                ScopedSubscriptionListMutation.collapseMutations(subscriptions)
            )
        } returns RequestResult(
            status = 200, value = null, body = null, headers = emptyMap()
        )

        val result = contactManager.performNextOperation()
        assertEquals(true, result)

        coVerify {
            mockApiClient.update(
                anonIdentityResult.contactId,
                TagGroupsMutation.collapseMutations(tags),
                AttributeMutation.collapseMutations(attributes),
                ScopedSubscriptionListMutation.collapseMutations(subscriptions)
            )
        }
    }

    @Test
    public fun testRegisterEmail(): TestResult = runTest {
        val address = "some address"
        val options = EmailRegistrationOptions.options(null, null, true)

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerEmail(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 200,
            value = AssociatedChannel("some channel", ChannelType.EMAIL),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterEmail(address, options))
        assertEquals(true, contactManager.performNextOperation())

        coVerify {
            mockApiClient.registerEmail(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        }
    }

    @Test
    public fun testRegisterEmailClientError(): TestResult = runTest {
        val address = "some address"
        val options = EmailRegistrationOptions.options(null, null, true)

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerEmail(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 400, value = null, body = null, headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterEmail(address, options))
        assertEquals(true, contactManager.performNextOperation())
    }

    @Test
    public fun testRegisterEmailServerError(): TestResult = runTest {
        val address = "some address"
        val options = EmailRegistrationOptions.options(null, null, true)

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerEmail(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 500, value = null, body = null, headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterEmail(address, options))
        assertEquals(false, contactManager.performNextOperation())
    }

    @Test
    public fun testRegisterSms(): TestResult = runTest {
        val address = "some address"
        val options = SmsRegistrationOptions.options("some sender id")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerSms(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 200,
            value = AssociatedChannel("some channel", ChannelType.EMAIL),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterSms(address, options))
        assertEquals(true, contactManager.performNextOperation())

        coVerify {
            mockApiClient.registerSms(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        }
    }

    @Test
    public fun testRegisterSmsClientError(): TestResult = runTest {
        val address = "some address"
        val options = SmsRegistrationOptions.options("some sender id")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerSms(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 400, value = null, body = null, headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterSms(address, options))
        assertEquals(true, contactManager.performNextOperation())
    }

    @Test
    public fun testRegisterSmsServerError(): TestResult = runTest {
        val address = "some address"
        val options = SmsRegistrationOptions.options("some sender id")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerSms(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 500, value = null, body = null, headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterSms(address, options))
        assertEquals(false, contactManager.performNextOperation())
    }

    @Test
    public fun testRegisterOpen(): TestResult = runTest {
        val address = "some address"
        val options = OpenChannelRegistrationOptions.options("some platform")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerOpen(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 200,
            value = AssociatedChannel("some channel", ChannelType.EMAIL),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterOpen(address, options))
        assertEquals(true, contactManager.performNextOperation())

        coVerify {
            mockApiClient.registerOpen(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        }
    }

    @Test
    public fun testRegisterOpenClientError(): TestResult = runTest {
        val address = "some address"
        val options = OpenChannelRegistrationOptions.options("some platform")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerOpen(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 400,
            value = AssociatedChannel("some channel", ChannelType.EMAIL),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterOpen(address, options))
        assertEquals(true, contactManager.performNextOperation())
    }

    @Test
    public fun testRegisterOpenServerError(): TestResult = runTest {
        val address = "some address"
        val options = OpenChannelRegistrationOptions.options("some platform")

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.registerOpen(
                anonIdentityResult.contactId, address, options, Locale.ENGLISH
            )
        } returns RequestResult(
            status = 500,
            value = AssociatedChannel("some channel", ChannelType.EMAIL),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.RegisterOpen(address, options))
        assertEquals(false, contactManager.performNextOperation())
    }

    @Test
    public fun testAssociateChannel(): TestResult = runTest {
        val address = "some address"
        val type = ChannelType.SMS

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.associatedChannel(
                anonIdentityResult.contactId, address, type
            )
        } returns RequestResult(
            status = 200,
            value = AssociatedChannel(address, type),
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.AssociateChannel(address, type))
        assertEquals(true, contactManager.performNextOperation())

        coVerify { mockApiClient.associatedChannel(anonIdentityResult.contactId, address, type) }
    }

    @Test
    public fun testAssociateChannelClientError(): TestResult = runTest {
        val address = "some address"
        val type = ChannelType.SMS

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.associatedChannel(
                anonIdentityResult.contactId, address, type
            )
        } returns RequestResult(
            status = 400,
            value = null,
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.AssociateChannel(address, type))
        assertEquals(true, contactManager.performNextOperation())
    }

    @Test
    public fun testAssociateChannelServerError(): TestResult = runTest {
        val address = "some address"
        val type = ChannelType.SMS

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.associatedChannel(
                anonIdentityResult.contactId, address, type
            )
        } returns RequestResult(
            status = 500,
            value = null,
            body = null,
            headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.AssociateChannel(address, type))
        assertEquals(false, contactManager.performNextOperation())
    }

    @Test
    public fun testAudienceOverrides(): TestResult = runTest {
        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )
        contactManager.addOperation(ContactOperation.Resolve)
        assertEquals(true, contactManager.performNextOperation())

        val subscriptions = listOf(
            ScopedSubscriptionListMutation.newUnsubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            ), ScopedSubscriptionListMutation.newSubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            )
        )
        val tags = listOf(
            TagGroupsMutation.newAddTagsMutation("some group", setOf("some tag")),
            TagGroupsMutation.newRemoveTagsMutation("some group", setOf("some tag"))
        )
        val attributes = listOf(
            AttributeMutation.newRemoveAttributeMutation(
                "some attribute", testClock.currentTimeMillis
            ), AttributeMutation.newSetAttributeMutation(
                "some attribute", JsonValue.wrap("some value"), testClock.currentTimeMillis
            )
        )

        contactManager.addOperation(ContactOperation.Update(tags = tags))
        contactManager.addOperation(ContactOperation.Update(attributes = attributes))
        contactManager.addOperation(ContactOperation.Update(subscriptions = subscriptions))

        assertEquals(
            AudienceOverrides.Contact(
                tags = tags, attributes = attributes, subscriptions = subscriptions
            ), audienceOverridesProvider.contactOverrides(anonIdentityResult.contactId)
        )
    }

    @Test
    public fun testConflict(): TestResult = runTest {
        val subscriptions = listOf(
            ScopedSubscriptionListMutation.newUnsubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            ), ScopedSubscriptionListMutation.newSubscribeMutation(
                "some list", Scope.APP, testClock.currentTimeMillis
            )
        )
        val tags = listOf(
            TagGroupsMutation.newAddTagsMutation("some group", setOf("some tag")),
            TagGroupsMutation.newRemoveTagsMutation("some group", setOf("some tag"))
        )
        val attributes = listOf(
            AttributeMutation.newRemoveAttributeMutation(
                "some attribute", testClock.currentTimeMillis
            ), AttributeMutation.newSetAttributeMutation(
                "some attribute", JsonValue.wrap("some value"), testClock.currentTimeMillis
            )
        )

        // Resolve
        coEvery { mockApiClient.resolve("some channel id", null) } returns RequestResult(
            status = 200, value = anonIdentityResult, body = null, headers = emptyMap()
        )

        // Identify
        coEvery {
            mockApiClient.identify(
                "some channel id", anonIdentityResult.contactId, "some named user id"
            )
        } returns RequestResult(
            status = 200, value = nonAnonIdentifyResult, body = null, headers = emptyMap()
        )

        // Register
        coEvery {
            mockApiClient.associatedChannel(
                anonIdentityResult.contactId, "some address", ChannelType.SMS
            )
        } returns RequestResult(
            status = 200,
            value = AssociatedChannel("some address", ChannelType.SMS),
            body = null,
            headers = emptyMap()
        )

        // Update
        coEvery {
            mockApiClient.update(
                anonIdentityResult.contactId,
                TagGroupsMutation.collapseMutations(tags),
                AttributeMutation.collapseMutations(attributes),
                ScopedSubscriptionListMutation.collapseMutations(subscriptions)
            )
        } returns RequestResult(
            status = 200, value = null, body = null, headers = emptyMap()
        )

        contactManager.addOperation(ContactOperation.Update(tags = tags))
        contactManager.addOperation(ContactOperation.Update(attributes = attributes))
        contactManager.addOperation(ContactOperation.Update(subscriptions = subscriptions))
        contactManager.addOperation(ContactOperation.AssociateChannel("some address", ChannelType.SMS))

        contactManager.addOperation(ContactOperation.Identify("some named user id"))

        // Resolve & Update
        assertEquals(true, contactManager.performNextOperation())

        // Associate
        assertEquals(true, contactManager.performNextOperation())

        // Identify
        assertEquals(true, contactManager.performNextOperation())

        val expectedConflictEvent = ConflictEvent(
            attributes = mapOf(
                "some attribute" to JsonValue.wrapOpt("some value")
            ),
            subscriptionLists = mapOf(
                "some list" to setOf(Scope.APP)
            ),
            associatedChannels = listOf(AssociatedChannel("some address", ChannelType.SMS)),
            conflictingNameUserId = "some named user id"
        )

        contactManager.conflictEvents.receiveAsFlow().test {
           assertEquals(expectedConflictEvent, this.awaitItem())
        }
    }
}
