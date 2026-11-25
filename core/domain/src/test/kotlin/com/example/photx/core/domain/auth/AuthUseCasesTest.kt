package com.example.photx.core.domain.auth

import app.cash.turbine.test
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUseCasesTest {
    @Test
    fun `given blank phone number_when submitting_then returns validation error without calling repository`() =
        runTest {
            val repo = FakeAuthRepository()
            val useCase = SubmitPhoneNumberUseCase(repo)

            val result = useCase("   ")

            assertTrue(result is Outcome.Failure)
            assertTrue((result as Outcome.Failure).error is AppError.Validation)
            assertNull(repo.lastSubmittedPhone)
        }

    @Test
    fun `given formatted phone number_when submitting_then normalizes separators and delegates`() = runTest {
        val repo = FakeAuthRepository()
        val useCase = SubmitPhoneNumberUseCase(repo)

        val result = useCase(" +1 (555) 123-4567 ")

        assertEquals(Outcome.Success(Unit), result)
        assertEquals("+15551234567", repo.lastSubmittedPhone)
    }

    @Test
    fun `given too short or non-digit phone number_when submitting_then returns validation error`() = runTest {
        val repo = FakeAuthRepository()
        val useCase = SubmitPhoneNumberUseCase(repo)

        val shortResult = useCase("12345")
        val alphaResult = useCase("+1555ABCDEFG")

        assertTrue(shortResult is Outcome.Failure)
        assertTrue(alphaResult is Outcome.Failure)
        assertNull(repo.lastSubmittedPhone)
    }

    @Test
    fun `given non-numeric or blank otp code_when submitting_then returns validation error`() = runTest {
        val repo = FakeAuthRepository()
        val useCase = SubmitAuthCodeUseCase(repo)

        assertTrue(useCase("") is Outcome.Failure)
        assertTrue(useCase("12ab5") is Outcome.Failure)
        assertNull(repo.lastSubmittedCode)
    }

    @Test
    fun `given numeric otp with whitespace_when submitting_then trims and delegates`() = runTest {
        val repo = FakeAuthRepository()
        val useCase = SubmitAuthCodeUseCase(repo)

        val result = useCase(" 54321 ")

        assertEquals(Outcome.Success(Unit), result)
        assertEquals("54321", repo.lastSubmittedCode)
    }

    @Test
    fun `given password with spaces_when submitting_then preserves exact password characters`() = runTest {
        val repo = FakeAuthRepository()
        val useCase = SubmitPasswordUseCase(repo)

        val emptyResult = useCase("")
        val validResult = useCase(" pass phrase ")

        assertTrue(emptyResult is Outcome.Failure)
        assertEquals(Outcome.Success(Unit), validResult)
        assertEquals(" pass phrase ", repo.lastSubmittedPassword)
    }

    @Test
    fun `given auth state changes_when observing_then emits updates in order`() = runTest {
        val repo = FakeAuthRepository(AuthState.Initializing)
        val observe = ObserveAuthStateUseCase(repo)

        observe().test {
            assertEquals(AuthState.Initializing, awaitItem())
            repo.emitState(AuthState.WaitingForPhoneNumber)
            assertEquals(AuthState.WaitingForPhoneNumber, awaitItem())
            repo.emitState(AuthState.WaitingForCode(codeLength = 5, timeoutSeconds = 60))
            assertEquals(AuthState.WaitingForCode(codeLength = 5, timeoutSeconds = 60), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
