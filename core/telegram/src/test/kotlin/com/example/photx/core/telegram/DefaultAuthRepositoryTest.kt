package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.auth.AuthState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthRepositoryTest {
    private class FakeConfigProvider(private var config: TdlibParametersConfig) : TdlibConfigProvider {
        override fun createParametersConfig(): TdlibParametersConfig = config
    }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatcherProvider {
        val dispatcher = StandardTestDispatcher(scheduler)
        return object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        }
    }

    private fun validConfig(apiId: Int = 12345, apiHash: String = "0123456789abcdef"): TdlibParametersConfig =
        TdlibParametersConfig(
            apiId = apiId,
            apiHash = apiHash,
            databaseDirectory = "/tmp/tdlib/db",
            filesDirectory = "/tmp/tdlib/files",
            systemLanguageCode = "en",
            deviceModel = "Pixel",
            systemVersion = "16",
            applicationVersion = "0.1.0"
        )

    @Test
    fun `given missing credentials_when initialize_then emits validation error without starting gateway`() = runTest {
        val gateway = FakeTdlibGateway()
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(validConfig(apiId = 0, apiHash = "")),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        val result = repo.initialize()

        assertTrue(result is Outcome.Failure)
        assertEquals(0, gateway.startCalls)
        assertTrue(repo.authState.value is AuthState.Error)
        assertTrue((repo.authState.value as AuthState.Error).error is AppError.Validation)
    }

    @Test
    fun `given wait parameters event_when initialized_then sends parameters automatically`() = runTest {
        val gateway = FakeTdlibGateway()
        val expectedConfig = validConfig()
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(expectedConfig),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        val result = repo.initialize()
        assertEquals(Outcome.Success(Unit), result)

        gateway.emitAuthEvent(TdlibAuthEvent.WaitParameters)
        advanceUntilIdle()

        assertEquals(listOf(expectedConfig), gateway.sentParameters)
    }

    @Test
    fun `given full login flow_when ready emitted_then resolves authenticated account label`() = runTest {
        val gateway = FakeTdlibGateway().apply {
            fetchUserLabelResult = Outcome.Success("@alex_photos")
        }
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(validConfig()),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        repo.initialize()
        gateway.emitAuthEvent(TdlibAuthEvent.WaitPhoneNumber)
        assertEquals(AuthState.WaitingForPhoneNumber, repo.authState.value)

        assertEquals(Outcome.Success(Unit), repo.submitPhoneNumber(" +15551234567 "))
        assertEquals(listOf("+15551234567"), gateway.sentPhoneNumbers)

        gateway.emitAuthEvent(TdlibAuthEvent.WaitCode(codeLength = 5, timeoutSeconds = 60))
        assertEquals(AuthState.WaitingForCode(codeLength = 5, timeoutSeconds = 60), repo.authState.value)

        assertEquals(Outcome.Success(Unit), repo.submitAuthenticationCode(" 12345 "))
        assertEquals(listOf("12345"), gateway.sentCodes)

        gateway.emitAuthEvent(
            TdlibAuthEvent.WaitPassword(
                passwordHint = "pet name",
                hasRecoveryEmailAddress = true,
                recoveryEmailAddressPattern = "a***@example.com"
            )
        )
        assertEquals(
            AuthState.WaitingForPassword(
                passwordHint = "pet name",
                hasRecoveryEmailAddress = true,
                recoveryEmailAddressPattern = "a***@example.com"
            ),
            repo.authState.value
        )

        assertEquals(Outcome.Success(Unit), repo.submitPassword("secret"))
        assertEquals(listOf("secret"), gateway.sentPasswords)

        gateway.emitAuthEvent(TdlibAuthEvent.Ready)
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated("@alex_photos"), repo.authState.value)
    }

    @Test
    fun `given blank inputs_when submitting_then returns validation failure without calling gateway`() = runTest {
        val gateway = FakeTdlibGateway()
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(validConfig()),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        assertTrue(repo.submitPhoneNumber("   ") is Outcome.Failure)
        assertTrue(repo.submitAuthenticationCode("") is Outcome.Failure)
        assertTrue(repo.submitPassword("") is Outcome.Failure)
        assertTrue(gateway.sentPhoneNumbers.isEmpty())
        assertTrue(gateway.sentCodes.isEmpty())
        assertTrue(gateway.sentPasswords.isEmpty())
    }

    @Test
    fun `given logout and closed_when reinitialized_then starts fresh client and clears label`() = runTest {
        val gateway = FakeTdlibGateway().apply {
            fetchUserLabelResult = Outcome.Success("@first_user")
        }
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(validConfig()),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        repo.initialize()
        gateway.emitAuthEvent(TdlibAuthEvent.Ready)
        advanceUntilIdle()
        assertEquals(AuthState.Authenticated("@first_user"), repo.authState.value)

        assertEquals(Outcome.Success(Unit), repo.logOut())
        assertEquals(1, gateway.logOutCalls)

        gateway.emitAuthEvent(TdlibAuthEvent.LoggingOut)
        assertEquals(AuthState.LoggingOut, repo.authState.value)

        gateway.emitAuthEvent(TdlibAuthEvent.Closed)
        assertEquals(AuthState.Closed, repo.authState.value)

        gateway.fetchUserLabelResult = Outcome.Success("@second_user")
        assertEquals(Outcome.Success(Unit), repo.initialize())
        assertEquals(2, gateway.startCalls)
        assertEquals(AuthState.Initializing, repo.authState.value)
    }

    @Test
    fun `given sendTdlibParameters failure_when wait parameters_then sets error state`() = runTest {
        val gateway = FakeTdlibGateway().apply {
            sendParametersResult = Outcome.Failure(AppError.Network("Offline"))
        }
        val repo = DefaultAuthRepository(
            gateway = gateway,
            configProvider = FakeConfigProvider(validConfig()),
            dispatcherProvider = testDispatchers(testScheduler)
        )

        repo.initialize()
        gateway.emitAuthEvent(TdlibAuthEvent.WaitParameters)
        advanceUntilIdle()

        assertEquals(AuthState.Error(AppError.Network("Offline")), repo.authState.value)
    }
}
