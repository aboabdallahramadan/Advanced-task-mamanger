package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun RegisterScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToLogin: () -> Unit,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val shapes = LocalTmapShapes.current

    TmapBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Amber wordmark
            Text(
                text = "TMap",
                style = type.display,
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Card container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = colors.surface,
                        shape = RoundedCornerShape(shapes.card),
                    )
                    .padding(24.dp),
            ) {
                Column {
                    Text(
                        text = "Create your account",
                        style = type.heading,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Start planning your days with TMap.",
                        style = type.body,
                        color = colors.textSecondary,
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

                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        disabledTextColor = colors.textTertiary,
                        focusedContainerColor = colors.surfaceInset,
                        unfocusedContainerColor = colors.surfaceInset,
                        disabledContainerColor = colors.surfaceInset,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.borderStrong,
                        disabledBorderColor = colors.borderSubtle,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.textTertiary,
                        disabledLabelColor = colors.textTertiary,
                        errorBorderColor = colors.danger,
                        errorLabelColor = colors.danger,
                        errorTextColor = colors.textPrimary,
                        errorContainerColor = colors.surfaceInset,
                        cursorColor = colors.accent,
                    )

                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = fieldColors,
                        textStyle = type.body,
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
                        colors = fieldColors,
                        textStyle = type.body,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        text = "Use ${AuthUiState.MIN_PASSWORD}+ characters. A passphrase is stronger than a short complex password.",
                        style = type.meta,
                        color = if (state.passwordTooShort) colors.danger else colors.textTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    if (state.submitting) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = colors.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        PrimaryButton(
                            text = "Create account",
                            onClick = onSubmit,
                            enabled = state.canSubmit,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    }

                    Text(
                        text = "Already have an account? Sign in",
                        style = type.body,
                        color = if (!state.submitting) colors.accent else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clickable(
                                enabled = !state.submitting,
                                role = Role.Button,
                                onClick = onSwitchToLogin,
                            )
                            .padding(vertical = 4.dp),
                    )
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
