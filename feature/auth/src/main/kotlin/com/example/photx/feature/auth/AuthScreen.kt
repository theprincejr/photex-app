package com.example.photx.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hilt-connected route entry point for [AuthScreen].
 */
@Composable
public fun AuthRoute(modifier: Modifier = Modifier, viewModel: AuthViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AuthScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}

/**
 * Stateless Material 3 authentication screen rendering phone, OTP, 2FA password, and status steps.
 */
@Composable
public fun AuthScreen(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.auth_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.errorMessage != null && uiState.step != AuthStep.ERROR) {
            AuthErrorBanner(
                message = uiState.errorMessage,
                onDismiss = { onEvent(AuthUiEvent.DismissError) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        AuthStepContent(uiState = uiState, onEvent = onEvent)
    }
}

@Composable
private fun AuthStepContent(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    when (uiState.step) {
        AuthStep.INITIALIZING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.auth_initializing))
        }

        AuthStep.PHONE_NUMBER -> PhoneStep(uiState = uiState, onEvent = onEvent)

        AuthStep.CODE -> CodeStep(uiState = uiState, onEvent = onEvent)

        AuthStep.PASSWORD -> PasswordStep(uiState = uiState, onEvent = onEvent)

        AuthStep.AUTHENTICATED -> AuthenticatedStep(uiState = uiState, onEvent = onEvent)

        AuthStep.CLOSED -> StatusRetryStep(
            message = stringResource(R.string.auth_closed_message),
            onRetry = { onEvent(AuthUiEvent.RetryInitialization) }
        )

        AuthStep.ERROR -> StatusRetryStep(
            message = uiState.errorMessage.orEmpty(),
            onRetry = { onEvent(AuthUiEvent.RetryInitialization) }
        )
    }
}

@Composable
private fun PhoneStep(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    OutlinedTextField(
        value = uiState.phoneNumberInput,
        onValueChange = { onEvent(AuthUiEvent.PhoneNumberChanged(it)) },
        label = { Text(stringResource(R.string.auth_phone_label)) },
        placeholder = { Text(stringResource(R.string.auth_phone_placeholder)) },
        singleLine = true,
        enabled = !uiState.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { onEvent(AuthUiEvent.SubmitPhoneNumber) },
        enabled = !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.auth_send_code))
    }
}

@Composable
private fun CodeStep(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    if (uiState.codeLength != null) {
        Text(
            text = stringResource(R.string.auth_code_hint_digits, uiState.codeLength),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    OutlinedTextField(
        value = uiState.codeInput,
        onValueChange = { onEvent(AuthUiEvent.CodeChanged(it)) },
        label = { Text(stringResource(R.string.auth_code_label)) },
        singleLine = true,
        enabled = !uiState.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { onEvent(AuthUiEvent.SubmitCode) },
        enabled = !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.auth_verify_code))
    }
}

@Composable
private fun PasswordStep(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    if (uiState.passwordHint.isNotBlank()) {
        Text(
            text = stringResource(R.string.auth_password_hint, uiState.passwordHint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    OutlinedTextField(
        value = uiState.passwordInput,
        onValueChange = { onEvent(AuthUiEvent.PasswordChanged(it)) },
        label = { Text(stringResource(R.string.auth_password_label)) },
        singleLine = true,
        enabled = !uiState.isSubmitting,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { onEvent(AuthUiEvent.SubmitPassword) },
        enabled = !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.auth_verify_password))
    }
}

@Composable
private fun AuthenticatedStep(uiState: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    Text(
        text = stringResource(R.string.auth_connected_title),
        style = MaterialTheme.typography.titleMedium
    )
    if (uiState.accountDisplayLabel.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_connected_account, uiState.accountDisplayLabel),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = { onEvent(AuthUiEvent.LogOut) },
        enabled = !uiState.isSubmitting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.auth_sign_out))
    }
}

@Composable
private fun StatusRetryStep(message: String, onRetry: () -> Unit) {
    if (message.isNotBlank()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.auth_retry))
    }
}

@Composable
private fun AuthErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.auth_dismiss))
            }
        }
    }
}
