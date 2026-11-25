package com.example.photx.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true)
@Composable
private fun AuthScreenPhonePreview() {
    MaterialTheme {
        AuthScreen(
            uiState = AuthUiState(step = AuthStep.PHONE_NUMBER, phoneNumberInput = "+15551234567"),
            onEvent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthScreenCodePreview() {
    MaterialTheme {
        AuthScreen(
            uiState = AuthUiState(step = AuthStep.CODE, codeLength = 5),
            onEvent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthScreenPasswordPreview() {
    MaterialTheme {
        AuthScreen(
            uiState = AuthUiState(step = AuthStep.PASSWORD, passwordHint = "favorite pet"),
            onEvent = {}
        )
    }
}
