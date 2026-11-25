package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Signs out of the currently authenticated Telegram account.
 */
public class LogOutUseCase(private val authRepository: AuthRepository) {
    /** Requests account sign-out through [AuthRepository]. */
    public suspend operator fun invoke(): Outcome<Unit, AppError> = authRepository.logOut()
}
