package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun RegisterScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToLogin: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Create your account", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start planning your days with TMap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )

                    if (state.networkError) {
                        AuthBanner(
                            text = "Couldn't reach the server. Check your connection and try again.",
                            isError = false,
                        )
                    } else if (state.errorMessage != null) {
                        AuthBanner(text = state.errorMessage, isError = true)
                    }

                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        isError = state.passwordTooShort,
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "Use ${AuthUiState.MIN_PASSWORD}+ characters. A passphrase is stronger than a short complex password.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.passwordTooShort) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Button(
                        onClick = onSubmit,
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Creating account…")
                        } else {
                            Text("Create account")
                        }
                    }

                    TextButton(
                        onClick = onSwitchToLogin,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("Already have an account? Sign in")
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun RegisterScreenPreview() {
    TmapTheme {
        RegisterScreen(
            state = AuthUiState(mode = AuthMode.Register, email = "you@example.com", password = "short"),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onSwitchToLogin = {},
        )
    }
}
