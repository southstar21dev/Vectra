package dev.southstar21.vectra.features.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.components.launchCatching
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.icons.VectraLogo
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tint
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** The sign-in card stays card-sized on wide windows. */
private val LoginCardWidth = 448.dp

enum class AuthMode { Login, Signup }

/** Sign-in / sign-up screen. */
@Composable
fun LoginScreen(auth: AuthRepository, passkeys: PasskeyActions? = null) {
    var mode by remember { mutableStateOf(AuthMode.Login) }
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Reveal(0, Modifier.widthIn(max = LoginCardWidth).fillMaxWidth()) {
            AuthCard(mode, { mode = it }, auth, passkeys)
        }
    }
}

@Composable
private fun AuthCard(mode: AuthMode, onMode: (AuthMode) -> Unit, auth: AuthRepository, passkeys: PasskeyActions?) {
    val c = Vectra.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Brand owl with its purple drop-shadow glow; inverted to dark in light mode.
        Box(
            Modifier.size(128.dp).drawBehind {
                drawCircle(
                    Brush.radialGradient(0f to rgba(168, 85, 247, 0.45f), 1f to Color.Transparent, radius = size.minDimension * 0.55f),
                    radius = size.minDimension * 0.55f,
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            VIcon(VectraLogo, size = 128.dp, tint = if (c.isDark) Color.White else Color.Black)
        }
        val shape = Radius.Card
        val depth = cardDepthShadows(c.isDark)
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(280, easing = VectraEasing.Decelerate))
                .boxShadow(shape, depth)
                .background(c.surfaceSecondary, shape)
                .background(
                    Brush.linearGradient(
                        0f to rgba(168, 85, 247, if (c.isDark) 0.10f else 0.08f),
                        0.45f to Color.Transparent,
                        start = Offset(0f, 0f),
                        end = Offset(600f, 1000f),
                    ),
                    shape,
                )
                .insetShadow(shape, depth)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        ) {
            VTabs(
                mode,
                AuthMode.entries,
                onMode,
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) { m, _ -> VText(if (m == AuthMode.Login) "Sign in" else "Create account", maxLines = 1) }
            AnimatedContent(
                mode,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { 18 }) togetherWith
                        (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -18 })
                },
                label = "authMode",
            ) { m ->
                if (m == AuthMode.Login) LoginForm(auth, passkeys) else SignupForm(auth, passkeys)
            }
        }
    }
}

/** Optional passkey hooks (Credential Manager), wired in by the platform. */
interface PasskeyActions {
    val isSupported: Boolean
    suspend fun signIn()
    suspend fun register()
    fun errorMessage(e: Throwable): String?
}

@Composable
private fun Banner(text: String, tint: Tint, fill: Color) {
    val shape = Radius.Control
    Row(
        Modifier.fillMaxWidth().background(fill, shape).border(1.dp, tint.border, shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.ErrorWarningLine, tint = tint.text)
        VText(text, style = vtext(TypeScale.Caption), color = tint.text)
    }
}

@Composable
private fun FieldLabel(text: String) =
    VText(text, style = vtext(TypeScale.Caption, Weight.Bold), color = Vectra.colors.textSecondary)

@Composable
private fun PasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    VIcon(
        if (visible) RemixIcons.EyeOffLine else RemixIcons.EyeLine,
        Modifier.tappable(role = null, onClick = onToggle).padding(4.dp),
        tint = Vectra.colors.textSecondary,
        contentDescription = if (visible) "Hide password" else "Show password",
    )
}

@Composable
private fun LoginForm(auth: AuthRepository, passkeys: PasskeyActions?) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var passkeyPending by remember { mutableStateOf(false) }
    var passkeyError by remember { mutableStateOf<String?>(null) }
    val sessionExpired = remember { auth.sessionExpired.value.also { auth.consumeSessionExpired() } }

    val submit = {
        if (!pending && email.isNotBlank() && password.isNotEmpty()) {
            pending = true
            error = null
            scope.launchCatching({ auth.login(email.trim(), password); pending = false }) { pending = false; error = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (sessionExpired) Banner("Your session expired — sign in again to continue.", c.tints.amber, Palette.Amber500.copy(alpha = 0.1f))
        if (error != null) Banner(AccountLogic.loginErrorMessage(error), c.tints.red, Palette.Red500.copy(alpha = 0.1f))
        if (passkeys?.isSupported == true) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VButton(
                    onClick = {
                        passkeyPending = true
                        passkeyError = null
                        scope.launchCatching({ passkeys.signIn(); passkeyPending = false }) { passkeyPending = false; passkeyError = passkeys.errorMessage(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline,
                    enabled = !passkeyPending,
                ) {
                    if (passkeyPending) InlineLoader() else {
                        VIcon(RemixIcons.FingerprintLine)
                        VText("Continue with biometrics", style = vtext(TypeScale.Caption, Weight.Bold))
                    }
                }
                passkeyError?.let { Banner(it, c.tints.red, Palette.Red500.copy(alpha = 0.1f)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Email address")
            VInput(
                email, { email = it }, placeholder = "name@example.com", icon = RemixIcons.MailLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Password")
            VInput(
                password, { password = it }, placeholder = "••••••••", icon = RemixIcons.LockLine,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailing = { PasswordToggle(show) { show = !show } },
            )
        }
        VButton(onClick = { submit() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Primary, enabled = !pending) {
            if (pending) InlineLoader() else VText("Sign in")
        }
    }
}

@Composable
private fun SignupForm(auth: AuthRepository, passkeys: PasskeyActions?) {
    val c = Vectra.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var biometrics by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val toaster = LocalToaster.current

    val submit = {
        val invalid = AccountLogic.validatePassword(password)
        when {
            pending -> Unit
            name.isBlank() || email.isBlank() -> Unit
            invalid != null -> error = IllegalArgumentException(invalid)
            else -> {
                pending = true
                error = null
                scope.launchCatching({
                    auth.signup(email.trim(), password, name.trim().take(AccountLogic.FULL_NAME_MAX_LENGTH))
                    if (biometrics && passkeys?.isSupported == true) {
                        runSuspendCatching { passkeys.register() }.onFailure { toaster.show("You can set this up later in Settings") }
                    }
                    pending = false
                }) { pending = false; error = it }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        error?.let { Banner(AccountLogic.fieldErrorMessage(it) ?: it.message.orEmpty(), c.tints.red, Palette.Red500.copy(alpha = 0.1f)) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Nickname")
            VInput(name, { name = it.take(AccountLogic.FULL_NAME_MAX_LENGTH) }, placeholder = "Your nickname", icon = RemixIcons.UserLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Email address")
            VInput(email, { email = it }, placeholder = "name@example.com", icon = RemixIcons.MailLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Password")
            VInput(
                password, { password = it }, placeholder = "At least ${AccountLogic.MIN_PASSWORD_LENGTH} characters", icon = RemixIcons.LockLine,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailing = { PasswordToggle(show) { show = !show } },
            )
        }
        if (passkeys?.isSupported == true) {
            val shape = Radius.Control
            Row(
                Modifier.fillMaxWidth().background(c.surfaceOverlay, shape).border(1.dp, c.borderSubtle, shape).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    VText("Enable biometric sign-in", style = vtext(TypeScale.Caption, Weight.Bold), color = c.textSecondary)
                    VText("Sign in with your fingerprint instead of a password", Modifier.padding(top = 4.dp), vtext(TypeScale.Caption), c.textMuted)
                }
                VToggle(biometrics, { biometrics = it }, label = "Enable biometric sign-in for faster logins")
            }
        }
        VButton(onClick = { submit() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Primary, enabled = !pending) {
            if (pending) InlineLoader() else VText("Create account")
        }
    }
}
