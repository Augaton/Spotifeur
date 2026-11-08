@file:Suppress("LocalContextGetResourceValueCall") // A language change recreates the activity.

package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.spotifeur.AccountUi
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.ui.theme.GoogleSansRounded

@Composable
fun AccountScreen(model: SpotifeurModel, account: AccountUi) {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Spotifeur",
                    modifier = Modifier.padding(top = 32.dp),
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 48.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    ctx.getString(R.string.welcome),
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SignInForm(account, model)
            }
        }
    }
}

@Composable
private fun SignInForm(account: AccountUi, model: SpotifeurModel) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordMode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val working = account.working
    Column(Modifier.fillMaxWidth()) {
        run {
            val codeLength = account.codeLength
            when {
                codeLength != null -> {
                    Text(
                        account.codeEmail?.let { ctx.getString(R.string.code_prompt_email, it) }
                            ?: ctx.getString(R.string.code_prompt),
                        Modifier.padding(vertical = 12.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(codeLength) },
                        label = { Text(ctx.getString(R.string.code_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            autofill?.cancel()
                            focus.clearFocus()
                            model.submitCode(code)
                            code = ""
                        },
                        enabled = code.length == codeLength,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(ctx.getString(R.string.code_confirm)) }
                    TextButton(onClick = model::disconnectSpotify) { Text(ctx.getString(R.string.action_cancel)) }
                }
                else -> {
                    Text(
                        if (passwordMode) ctx.getString(R.string.login_password_hint)
                        else ctx.getString(R.string.login_code_hint),
                        Modifier.padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (passwordMode) ctx.getString(R.string.login_user) else ctx.getString(R.string.login_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !working,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentType = ContentType.Username + ContentType.EmailAddress
                        },
                    )
                    if (passwordMode) OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(ctx.getString(R.string.login_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !working,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics {
                            contentType = ContentType.Password
                        },
                    )
                    Button(
                        onClick = {
                            if (!passwordMode) autofill?.cancel()
                            focus.clearFocus()
                            if (passwordMode) model.connectSpotify(username, password)
                            else model.connectSpotifyEmail(username)
                            password = ""
                        },
                        enabled = !working && username.isNotBlank() && (!passwordMode || password.isNotEmpty()),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(when {
                            account.working -> ctx.getString(R.string.login_connecting)
                            passwordMode -> ctx.getString(R.string.login_with_password)
                            else -> ctx.getString(R.string.login_with_code)
                        })
                    }
                    TextButton(
                        onClick = { passwordMode = !passwordMode; password = "" },
                        enabled = !working,
                    ) { Text(if (passwordMode) ctx.getString(R.string.login_use_code) else ctx.getString(R.string.login_use_password)) }
                    if (account.working) TextButton(onClick = model::disconnectSpotify) { Text(ctx.getString(R.string.action_cancel)) }
                    account.error?.let {
                        Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
