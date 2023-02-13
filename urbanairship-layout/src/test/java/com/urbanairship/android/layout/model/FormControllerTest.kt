/* Copyright Airship and Contributors */
package com.urbanairship.android.layout.model

import app.cash.turbine.test
import app.cash.turbine.testIn
import com.urbanairship.android.layout.environment.ActionsRunner
import com.urbanairship.android.layout.environment.AttributeHandler
import com.urbanairship.android.layout.environment.FormType
import com.urbanairship.android.layout.environment.LayoutEvent
import com.urbanairship.android.layout.environment.LayoutEventHandler
import com.urbanairship.android.layout.environment.LayoutState
import com.urbanairship.android.layout.environment.ModelEnvironment
import com.urbanairship.android.layout.environment.Reporter
import com.urbanairship.android.layout.environment.SharedState
import com.urbanairship.android.layout.environment.State
import com.urbanairship.android.layout.environment.inputData
import com.urbanairship.android.layout.event.ReportingEvent
import com.urbanairship.android.layout.property.FormBehaviorType
import com.urbanairship.android.layout.reporting.AttributeName
import com.urbanairship.android.layout.reporting.DisplayTimer
import com.urbanairship.android.layout.reporting.FormData
import com.urbanairship.android.layout.util.jsonMapOf
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
public class FormControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val mockReporter: Reporter = mockk(relaxUnitFun = true)
    private val mockActionsRunner: ActionsRunner = mockk()
    private val mockAttributeHandler: AttributeHandler = mockk {
        every { update(any()) } returns Unit
    }
    private val mockDisplayTimer: DisplayTimer = mockk {
        every { time } returns System.currentTimeMillis()
    }
    private val testEventHandler = spyk(LayoutEventHandler(testScope))
    private val mockEnv: ModelEnvironment = mockk {
        every { reporter } returns mockReporter
        every { actionsRunner } returns mockActionsRunner
        every { attributeHandler } returns mockAttributeHandler
        every { displayTimer } returns mockDisplayTimer
        every { layoutState } returns LayoutState.EMPTY
        every { eventHandler } returns testEventHandler
        every { layoutEvents } returns testEventHandler.layoutEvents
        every { modelScope } returns testScope
    }
    private val mockView: AnyModel = mockk(relaxed = true)

    private val parentFormState = spyk(SharedState(State.Form(
        identifier = PARENT_FORM_ID, formType = FormType.Form, formResponseType = "form")
    ))

    private val childFormState = spyk(SharedState(State.Form(
        identifier = CHILD_FORM_ID, formType = FormType.Form, formResponseType = "form")
    ))

    private lateinit var formController: FormController

    @Before
    public fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    public fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    public fun testParentFormInit(): TestResult = runTest {
        initParentFormController()

        verify { mockEnv.layoutEvents }
        verify { parentFormState.changes }
    }

    @Test
    public fun testParentFormReportsDisplay(): TestResult = runTest {
        parentFormState.changes.test {
            assertFalse(awaitItem().isDisplayReported)

            initParentFormController()

            assertTrue(awaitItem().isDisplayReported)

            verify { mockReporter.report(any<ReportingEvent.FormDisplay>(), any()) }

            ensureAllEventsConsumed()
        }
    }

    @Test
    public fun testParentFormSubmit(): TestResult = runTest {
        parentFormState.changes.test {
            skipItems(1)

            // Init controller and verify initial state
            initParentFormController()
            assertTrue(awaitItem().data.isEmpty())

            // Update form data
            parentFormState.update {
                it.copyWithFormInput(
                    FormData.TextInput(
                        identifier = TEXT_INPUT_ID,
                        value = TEXT_INPUT_VALUE,
                        isValid = true,
                        attributeName = ATTR_NAME,
                        attributeValue = ATTR_VALUE
                    )
                )
            }
            assertFalse(awaitItem().data.isEmpty())

            // Emit FORM_SUBMIT event
            val event = spyk(LayoutEvent.SubmitForm(BUTTON_ID))
            testEventHandler.broadcast(event)
            // Verify state was updated
            assertTrue(awaitItem().isSubmitted)

            // Verify submit logic was called
            coVerify { event.onSubmitted }
            coVerify { mockAttributeHandler.update(eq(ATTRIBUTES)) }
            coVerify { mockReporter.report(any<ReportingEvent.FormResult>(), any()) }

            ensureAllEventsConsumed()
        }
    }

    @Test
    public fun testChildFormInit(): TestResult = runTest {
        initChildFormController()

        verify { childFormState.changes }
    }

    @Test
    public fun testChildFormDoesNotReportDisplay(): TestResult = runTest {
        childFormState.changes.test {
            assertFalse(awaitItem().isDisplayReported)

            initChildFormController()

            verify(exactly = 0) { mockReporter.report(any<ReportingEvent.FormDisplay>(), any()) }

            ensureAllEventsConsumed()
        }
    }

    @Test
    public fun testChildFormStateChangesUpdateParentForm(): TestResult = runTest {
        val parentChanges = parentFormState.changes.testIn(testScope)
        val childChanges = childFormState.changes.testIn(testScope)

        // Sanity check initial empty states
        assertTrue(parentChanges.awaitItem().data.isEmpty())
        assertTrue(childChanges.awaitItem().data.isEmpty())

        initChildFormController()

        parentChanges.awaitItem().run {
            // Verify that the parent form is aware of the child form, now that it's initialized.
            assertTrue(data.containsKey(CHILD_FORM_ID))
            // Sanity check: child form shouldn't have any data from inputs yet.
            assertEquals(0, inputData<FormData.Form>(CHILD_FORM_ID)?.children?.size)
        }

        childFormState.update { form ->
            form.copyWithFormInput(
                FormData.TextInput(
                    identifier = TEXT_INPUT_ID,
                    value = TEXT_INPUT_VALUE,
                    isValid = true
                )
            )
        }

        // Verify that the child state was updated appropriately.
        childChanges.awaitItem().run {
            assertTrue(data.containsKey(TEXT_INPUT_ID))
            val inputData = requireNotNull(inputData<FormData.TextInput>(TEXT_INPUT_ID))
            assertEquals(TEXT_INPUT_ID, inputData.identifier)
            assertEquals(TEXT_INPUT_VALUE, inputData.value)
        }

        // Verify that the child form input change caused an update to the parent form state.
        verify { parentFormState.update(any()) }

        // Verify that the text input from the child form made it to the parent form state.
        parentChanges.awaitItem().run {
            assertTrue(data.containsKey(CHILD_FORM_ID))
            assertEquals(1, inputData<FormData.Form>(CHILD_FORM_ID)?.children?.count {
                it.identifier == TEXT_INPUT_ID && it.value == TEXT_INPUT_VALUE
            })
        }

        parentChanges.ensureAllEventsConsumed()
        childChanges.ensureAllEventsConsumed()
    }

    private fun initParentFormController() {
        formController = FormController(
            view = mockView,
            formState = parentFormState,
            parentFormState = null,
            identifier = PARENT_FORM_ID,
            responseType = "form",
            submitBehavior = FormBehaviorType.SUBMIT_EVENT,
            environment = mockEnv
        )
        testScope.runCurrent()
    }

    private fun initChildFormController() {
        formController = FormController(
            view = mockView,
            formState = childFormState,
            parentFormState = parentFormState,
            identifier = CHILD_FORM_ID,
            responseType = "form",
            submitBehavior = null,
            environment = mockEnv
        )
        testScope.runCurrent()
    }

    private companion object {
        private const val PARENT_FORM_ID = "parent-form-identifier"
        private const val CHILD_FORM_ID = "child-form-identifier"
        private const val TEXT_INPUT_ID = "text-input-identifier"
        private const val TEXT_INPUT_VALUE = "no comment."
        private const val BUTTON_ID = "button-identifier"
        private const val CHANNEL_ID = "channel-identifier"

        private val ATTR_NAME = AttributeName(CHANNEL_ID, null)
        private val ATTR_VALUE = jsonMapOf("foo" to "bar").toJsonValue()
        private val ATTRIBUTES = mapOf(ATTR_NAME to ATTR_VALUE)
    }
}
