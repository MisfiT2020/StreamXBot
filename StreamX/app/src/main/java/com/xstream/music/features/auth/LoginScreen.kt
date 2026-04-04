package com.xstream.music.features.auth

import com.xstream.music.player.service.*
import com.xstream.music.player.manager.*
import com.xstream.music.ui.components.*
import com.xstream.music.realtime.websocket.*
import com.xstream.music.core.preferences.*
import com.xstream.music.core.cache.*
import com.xstream.music.core.utils.*
import com.xstream.music.data.model.*
import com.xstream.music.data.api.*
import com.xstream.music.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import com.xstream.music.core.preferences.AuthPreferences
import com.xstream.music.data.api.ApiPreferences
import com.xstream.music.data.model.LoginRequest
import com.xstream.music.data.model.UserData

enum class AuthMode {
    LOGIN, REGISTER, OTP
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LoginScreen(
    onLoginSuccess: (UserData) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var authMode by remember { mutableStateOf(AuthMode.LOGIN) }
    var useridStr by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (authMode) {
                            AuthMode.LOGIN -> "Login"
                            AuthMode.REGISTER -> "Register"
                            AuthMode.OTP -> "Verify OTP"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (authMode == AuthMode.OTP) {
                            authMode = AuthMode.REGISTER
                            errorMessage = null
                            successMessage = null
                        } else if (authMode == AuthMode.REGISTER) {
                            authMode = AuthMode.LOGIN
                            errorMessage = null
                            successMessage = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Connect to StreamX", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (authMode) {
                            AuthMode.LOGIN -> "Sign in with your StreamX account"
                            AuthMode.REGISTER -> "Create a new account and verify with OTP"
                            AuthMode.OTP -> "Enter the OTP sent to your Telegram"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (authMode != AuthMode.OTP) {
                        Spacer(modifier = Modifier.height(14.dp))
                        TabRow(
                            selectedTabIndex = if (authMode == AuthMode.LOGIN) 0 else 1,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            divider = {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            }
                        ) {
                            Tab(
                                selected = authMode == AuthMode.LOGIN,
                                onClick = {
                                    authMode = AuthMode.LOGIN
                                    errorMessage = null
                                    successMessage = null
                                },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text("Login", fontWeight = FontWeight.SemiBold) }
                            )
                            Tab(
                                selected = authMode == AuthMode.REGISTER,
                                onClick = {
                                    authMode = AuthMode.REGISTER
                                    errorMessage = null
                                    successMessage = null
                                },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text("Register", fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))

                    if (authMode == AuthMode.LOGIN) {
                        LoginTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = "Username",
                            placeholder = "Enter your username"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LoginTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password",
                            placeholder = "Enter your password",
                            isPassword = true
                        )
                    } else if (authMode == AuthMode.REGISTER) {
                        LoginTextField(
                            value = useridStr,
                            onValueChange = { useridStr = it.filter { char -> char.isDigit() } },
                            label = "Telegram User ID",
                            placeholder = "Enter your Telegram User ID",
                            keyboardType = KeyboardType.Number
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LoginTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = "Username",
                            placeholder = "Choose a username"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LoginTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password",
                            placeholder = "Choose a password",
                            isPassword = true
                        )
                    } else {
                        LoginTextField(
                            value = otp,
                            onValueChange = { otp = it },
                            label = "OTP",
                            placeholder = "Enter OTP",
                            keyboardType = KeyboardType.Number
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(errorMessage!!) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }
            if (successMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(successMessage!!) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        successMessage = null
                        val apiUrl = ApiPreferences.getApiUrl(context)
                        
                        when (authMode) {
                            AuthMode.LOGIN -> {
                                if (username.isBlank() || password.isBlank()) {
                                    errorMessage = "Please enter both username and password"
                                    isLoading = false
                                    return@launch
                                }
                                val response = loginUser(apiUrl, LoginRequest(username, password))
                                if (response.ok && response.token != null) {
                                    val userData = UserData(
                                        id = response.user_id ?: 0,
                                        token = response.token,
                                        firstName = response.first_name ?: username,
                                        profileUrl = response.profile_url ?: "",
                                        photoUrl = response.photo_url ?: ""
                                    )
                                    AuthPreferences.saveUser(context, userData)
                                    
                                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            val token = task.result
                                            scope.launch {
                                                registerFcmToken(apiUrl, token, userData.token)
                                            }
                                        }
                                    }
                                    onLoginSuccess(userData)
                                } else {
                                    errorMessage = "Invalid credentials. Please try again."
                                }
                            }
                            AuthMode.REGISTER -> {
                                val uid = useridStr.toLongOrNull()
                                if (uid == null || username.isBlank() || password.isBlank()) {
                                    errorMessage = "Please enter valid User ID, username, and password"
                                    isLoading = false
                                    return@launch
                                }
                                val res = registerUser(apiUrl, uid, username, password)
                                if (res.ok) {
                                    successMessage = "OTP sent! Please check your Telegram."
                                    authMode = AuthMode.OTP
                                } else {
                                    errorMessage = res.detail ?: "Registration failed"
                                }
                            }
                            AuthMode.OTP -> {
                                val uid = useridStr.toLongOrNull()
                                if (uid == null || otp.isBlank()) {
                                    errorMessage = "Please enter valid OTP"
                                    isLoading = false
                                    return@launch
                                }
                                val res = validateUser(apiUrl, uid, otp)
                                if (res.ok && res.token != null) {
                                    val userData = UserData(
                                        id = res.user_id ?: uid,
                                        token = res.token,
                                        firstName = res.first_name ?: username,
                                        profileUrl = res.profile_url ?: "",
                                        photoUrl = res.photo_url ?: ""
                                    )
                                    AuthPreferences.saveUser(context, userData)
                                    
                                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            val token = task.result
                                            scope.launch {
                                                registerFcmToken(apiUrl, token, userData.token)
                                            }
                                        }
                                    }
                                    onLoginSuccess(userData)
                                } else {
                                    errorMessage = res.detail ?: "Invalid OTP"
                                }
                            }
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    disabledContentColor = Color.Black.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(28.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = when (authMode) {
                            AuthMode.LOGIN -> "Login"
                            AuthMode.REGISTER -> "Register"
                            AuthMode.OTP -> "Verify & Login"
                        }, 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = Color.Gray) },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
    }
}
