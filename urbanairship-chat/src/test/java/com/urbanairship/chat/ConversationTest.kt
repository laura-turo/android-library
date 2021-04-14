package com.urbanairship.chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urbanairship.TestActivityMonitor
import com.urbanairship.TestApplication
import com.urbanairship.channel.AirshipChannel
import com.urbanairship.chat.api.ChatApiClient
import com.urbanairship.chat.api.ChatConnection
import com.urbanairship.chat.api.ChatResponse
import com.urbanairship.chat.data.ChatDao
import com.urbanairship.chat.data.ChatDatabase
import com.urbanairship.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ConversationTest {

    private lateinit var mockChannel: AirshipChannel
    private lateinit var mockConnection: ChatConnection
    private lateinit var mockApiClient: ChatApiClient
    private lateinit var chatDao: ChatDao
    private lateinit var testActivityMonitor: TestActivityMonitor

    private lateinit var conversation: Conversation
    private lateinit var chatListener: ChatConnection.ChatListener

    private lateinit var testScope: TestCoroutineScope
    private lateinit var testDispatcher: TestCoroutineDispatcher

    @Before
    fun setUp() {
        mockChannel = mock()
        mockConnection = mock()
        mockApiClient = mock()
        mockChannel = mock()

        chatDao = Room.inMemoryDatabaseBuilder(TestApplication.getApplication(), ChatDatabase::class.java).allowMainThreadQueries().build().chatDao()

        testDispatcher = TestCoroutineDispatcher()
        testScope = TestCoroutineScope(testDispatcher)

        Dispatchers.setMain(testDispatcher)

        testActivityMonitor = TestActivityMonitor()

        val captor = ArgumentCaptor.forClass(ChatConnection.ChatListener::class.java)
        conversation = Conversation(TestApplication.getApplication().preferenceDataStore, mockChannel, chatDao, mockConnection, mockApiClient, testActivityMonitor, testScope, testDispatcher)

        verify(mockConnection).chatListener = captor.capture()
        chatListener = captor.value

        conversation.isEnabled = true
        Mockito.clearInvocations(mockConnection)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        testDispatcher.cleanupTestCoroutines()
    }

    @Test
    fun testConnectionCreatesUvp() = testDispatcher.runBlockingTest {
        whenever(mockChannel.id).thenReturn("some-channel")

        testActivityMonitor.foreground()

        verify(mockApiClient).fetchUvp("some-channel")
    }

    @Test
    fun testConnectionRequestsConversation() = testDispatcher.runBlockingTest {
        whenever(mockChannel.id).thenReturn("some-channel")
        whenever(mockApiClient.fetchUvp("some-channel")).thenReturn("some-uvp")

        testActivityMonitor.foreground()

        verify(mockConnection).open("some-uvp")
        verify(mockConnection).fetchConversation()
    }

    @Test
    fun testForegroundConnects() = testDispatcher.runBlockingTest {
        whenever(mockChannel.id).thenReturn("some-channel")
        whenever(mockApiClient.fetchUvp("some-channel")).thenReturn("some-uvp")

        testActivityMonitor.foreground()

        verify(mockConnection).open("some-uvp")
    }

    @Test
    fun testSendMessageAfterSync() = testDispatcher.runBlockingTest {
        connect()

        val response = ChatResponse.ConversationLoaded(conversation = ChatResponse.ConversationLoaded.ConversationPayload(null))
        chatListener.onChatResponse(response)

        conversation.sendMessage("hello")
        verify(mockConnection).sendMessage(Mockito.eq("hello"), Mockito.isNull(), Mockito.anyString())
    }

    @Test
    fun testSendMessageBeforeSync() = testDispatcher.runBlockingTest {
        connect()
        conversation.sendMessage("hello")

        verify(mockConnection, Mockito.never()).sendMessage(Mockito.eq("hello"), Mockito.isNull(), Mockito.anyString())

        val response = ChatResponse.ConversationLoaded(conversation = ChatResponse.ConversationLoaded.ConversationPayload(null))
        chatListener.onChatResponse(response)

        verify(mockConnection).sendMessage(Mockito.eq("hello"), Mockito.isNull(), Mockito.anyString())
    }

    @Test
    fun testSendMessageBeforeConnect() = testDispatcher.runBlockingTest {
        conversation.sendMessage("hello")
        connect()

        verify(mockConnection, Mockito.never()).sendMessage(Mockito.eq("hello"), Mockito.isNull(), Mockito.anyString())

        val response = ChatResponse.ConversationLoaded(conversation = ChatResponse.ConversationLoaded.ConversationPayload(null))
        chatListener.onChatResponse(response)

        verify(mockConnection).sendMessage(Mockito.eq("hello"), Mockito.isNull(), Mockito.anyString())
    }

    @Test
    fun testBackgroundClosesConnectionIfPendingSent() = testDispatcher.runBlockingTest {
        connect()
        verify(mockConnection).open("some-uvp")

        testActivityMonitor.background()
        verify(mockConnection, Mockito.never()).close()

        val response = ChatResponse.ConversationLoaded(conversation = ChatResponse.ConversationLoaded.ConversationPayload(null))
        chatListener.onChatResponse(response)

        verify(mockConnection).close()
    }

    @Test
    fun testBackgroundPendingMessages() = testDispatcher.runBlockingTest {
        connect()
        conversation.sendMessage("hello")
        val requestId = chatDao.getPendingMessages()[0].messageId

        testActivityMonitor.background()
        verify(mockConnection, Mockito.never()).close()

        val response = ChatResponse.ConversationLoaded(conversation = ChatResponse.ConversationLoaded.ConversationPayload(null))
        chatListener.onChatResponse(response)
        verify(mockConnection, Mockito.never()).close()

        verify(mockConnection).sendMessage("hello", null, requestId)
        verify(mockConnection, Mockito.never()).close()

        val message = ChatResponse.Message("some-id", DateUtils.createIso8601TimeStamp(0), 1, "hello", null, requestId)
        val messageResponsePayload = ChatResponse.MessageReceived.MessageReceivedPayload(true, message)
        val messageResponse = ChatResponse.MessageReceived(messageResponsePayload)
        chatListener.onChatResponse(messageResponse)

        verify(mockConnection).close()
    }

    @Test
    fun testConnectionStatus() = testDispatcher.runBlockingTest {
        connect()
        Assert.assertFalse(conversation.isConnected)

        chatListener.onOpen()
        Assert.assertTrue(conversation.isConnected)

        chatListener.onClose(ChatConnection.CloseReason.Manual)
        Assert.assertFalse(conversation.isConnected)
    }

    @Test
    fun testConnectWhileDisabled() = testDispatcher.runBlockingTest {
        conversation.isEnabled = false
        connect()
        verify(mockConnection, never()).open(any())
    }

    @Test
    fun testDisableWhileConnected() = testDispatcher.runBlockingTest {
        connect()
        conversation.isEnabled = false
        verify(mockConnection).close()
    }

    private fun connect() {
        whenever(mockChannel.id).thenReturn("some-channel")
        whenever(mockApiClient.fetchUvp("some-channel")).thenReturn("some-uvp")

        testActivityMonitor.foreground()
        whenever(mockConnection.isOpenOrOpening).thenReturn(true)
    }
}
