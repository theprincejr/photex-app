package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.domain.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TdlibAuthStateMapperTest {
    @Test
    fun `given all internal auth events_when mapped to domain_then preserves expected states`() {
        assertEquals(
            AuthState.WaitingForParameters,
            TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.WaitParameters)
        )
        assertEquals(
            AuthState.WaitingForPhoneNumber,
            TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.WaitPhoneNumber)
        )
        assertEquals(
            AuthState.WaitingForCode(codeLength = 6, timeoutSeconds = 90),
            TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.WaitCode(codeLength = 6, timeoutSeconds = 90))
        )
        assertEquals(
            AuthState.WaitingForPassword("hint", hasRecoveryEmailAddress = true, "e***@x.com"),
            TdlibAuthStateMapper.toDomainState(
                TdlibAuthEvent.WaitPassword("hint", hasRecoveryEmailAddress = true, "e***@x.com")
            )
        )
        assertEquals(
            AuthState.Authenticated("@photx"),
            TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.Ready, "@photx")
        )
        assertEquals(AuthState.LoggingOut, TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.LoggingOut))
        assertEquals(AuthState.LoggingOut, TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.Closing))
        assertEquals(AuthState.Closed, TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.Closed))

        val unsupported = TdlibAuthStateMapper.toDomainState(TdlibAuthEvent.UnsupportedState("QR needed"))
        assertEquals(AuthState.Error(AppError.Unauthorized("QR needed")), unsupported)
    }

    @Test
    fun `given known tdlib error codes_when mapped_then produces typed non-leaking errors`() {
        assertTrue(TdlibAuthStateMapper.mapTdError(400, "PHONE_NUMBER_INVALID") is AppError.Validation)
        assertTrue(TdlibAuthStateMapper.mapTdError(400, "PHONE_CODE_INVALID") is AppError.Validation)
        assertTrue(TdlibAuthStateMapper.mapTdError(400, "PHONE_CODE_EXPIRED") is AppError.Validation)
        assertTrue(TdlibAuthStateMapper.mapTdError(400, "PASSWORD_HASH_INVALID") is AppError.Validation)
        assertTrue(TdlibAuthStateMapper.mapTdError(401, "SESSION_REVOKED") is AppError.Unauthorized)
        assertTrue(TdlibAuthStateMapper.mapTdError(403, "FORBIDDEN") is AppError.Unauthorized)
        assertTrue(TdlibAuthStateMapper.mapTdError(420, "FLOOD_WAIT_30") is AppError.Network)
        assertTrue(TdlibAuthStateMapper.mapTdError(408, "Request timeout") is AppError.Network)
        assertTrue(TdlibAuthStateMapper.mapTdError(502, "Bad gateway") is AppError.Network)
        assertTrue(TdlibAuthStateMapper.mapTdError(400, "CUSTOM_BAD_INPUT") is AppError.Validation)
        assertTrue(TdlibAuthStateMapper.mapTdError(499, "UNKNOWN") is AppError.Unexpected)
    }
}
