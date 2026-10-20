package ch.sonensei.cockpit.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.theme.CockpitTheme

/** Test tags of the login form. */
object LoginTags {
    const val SERVER_URL = "login:serverUrl"
    const val USERNAME = "login:username"
    const val PASSWORD = "login:password"
    const val CODE = "login:code"
    const val TOGGLE_FACTOR = "login:toggleFactor"
    const val SUBMIT = "login:submit"
    const val ERROR = "login:error"
    const val SCREEN = "screen:login"
}

@Composable
fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onCodeChange = viewModel::onCodeChange,
        onUseRecoveryCodeChange = viewModel::onUseRecoveryCodeChange,
        onSubmit = viewModel::submit,
    )
}

/** The login form: where (server) and who (account, second factor), then one action. Stateless. */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onUseRecoveryCodeChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !state.submitting
    Surface(modifier = modifier.fillMaxSize().testTag(LoginTags.SCREEN)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(R.string.login_server_section)
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = onServerUrlChange,
                            label = { Text(stringResource(R.string.login_server_url)) },
                            supportingText = { Text(stringResource(R.string.login_server_url_hint)) },
                            isError = state.error == LoginError.SERVER_URL_MALFORMED || state.error == LoginError.SERVER_URL_HTTPS_REQUIRED,
                            singleLine = true,
                            enabled = editable,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().testTag(LoginTags.SERVER_URL),
                        )
                    }
                }

                SectionLabel(R.string.login_account_section)
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.USERNAME),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    enabled = editable,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
                )
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(if (state.useRecoveryCode) R.string.login_recovery_code else R.string.login_totp)) },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = if (state.useRecoveryCode) KeyboardType.Ascii else KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.CODE),
                )
                TextButton(
                    onClick = { onUseRecoveryCodeChange(!state.useRecoveryCode) },
                    enabled = editable,
                    modifier = Modifier.testTag(LoginTags.TOGGLE_FACTOR),
                ) {
                    Text(stringResource(if (state.useRecoveryCode) R.string.login_use_totp else R.string.login_use_recovery_code))
                }

                state.error?.let { ErrorMessage(it) }

                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.SUBMIT),
                ) {
                    Text(
                        when {
                            state.retryAfterSeconds > 0 ->
                                pluralStringResource(R.plurals.login_retry_in, state.retryAfterSeconds.toInt(), state.retryAfterSeconds)
                            state.submitting -> stringResource(R.string.login_signing_in)
                            else -> stringResource(R.string.login_submit)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorMessage(error: LoginError) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(
                    LoginTags.ERROR,
                ).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(error.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@StringRes
private fun LoginError.messageRes(): Int =
    when (this) {
        LoginError.SERVER_URL_MALFORMED -> R.string.login_error_server_url_malformed
        LoginError.SERVER_URL_HTTPS_REQUIRED -> R.string.login_error_server_url_https_required
        LoginError.INVALID_REQUEST -> R.string.login_error_invalid_request
        LoginError.WRONG_CREDENTIALS -> R.string.login_error_wrong_credentials
        LoginError.TOO_MANY_ATTEMPTS -> R.string.login_error_too_many_attempts
        LoginError.NETWORK -> R.string.login_error_network
        LoginError.SERVER -> R.string.login_error_server
    }

@Preview
@Composable
private fun LoginScreenPreview() =
    CockpitTheme {
        LoginScreen(
            state =
                LoginUiState(
                    serverUrl = "https://cockpit.example.org/",
                    username = "alex",
                    error = LoginError.TOO_MANY_ATTEMPTS,
                    retryAfterSeconds = 42,
                ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onCodeChange = {},
            onUseRecoveryCodeChange = {},
            onSubmit = {},
        )
    }
