@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.loveq.podcast.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.loveq.podcast.data.LoginOutcome
import cn.loveq.podcast.data.RateLimitedException
import cn.loveq.podcast.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * 官网登录框。
 *
 * 只在真正撞上登录墙（或用户从设置主动进来）时出现；账号密码只交给官网的
 * `user.php?act=signin`，本机只保存登录成功后的 Cookie，不保存密码。
 *
 * 第一次登录一般不需要验证码；密码错几次后官网会返回 `captcha=1`，
 * 这时才显示验证码输入和图片。图片取不到也不挡路，仍可手输。
 */
@Composable
internal fun LoginDialog(
    initialUsername: String,
    onLogin: suspend (username: String, password: String, captcha: String) -> LoginOutcome,
    onLoadCaptcha: suspend () -> ByteArray?,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // 密码不 rememberSaveable：转屏/进程重建时宁可让用户重输，也不落进 SavedState
    var password by remember { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var captchaRequired by remember { mutableStateOf(false) }
    var captchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshCaptcha() {
        scope.launch {
            captchaImage = runCatching { onLoadCaptcha() }
                .getOrNull()
                ?.toImageBitmap()
        }
    }

    fun submit() {
        if (loading) return
        if (username.isBlank() || password.isBlank()) {
            error = "请输入账号和密码"
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                when (val result = onLogin(username.trim(), password, captcha.trim())) {
                    is LoginOutcome.Success -> onLoggedIn()
                    is LoginOutcome.CaptchaRequired -> {
                        captchaRequired = true
                        captcha = ""
                        error = result.message
                        captchaImage = result.image?.toImageBitmap()
                    }
                    is LoginOutcome.Failure -> error = result.message
                }
            } catch (e: RateLimitedException) {
                error = "官网限制了访问，请稍后再试"
            } catch (e: Exception) {
                error = "登录失败：${e.message ?: "网络异常"}"
            } finally {
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .widthIn(max = 460.dp),
        title = {
            Text(
                "登录 LoveQ",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Ink,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "官网从 2026-11-16 起要求登录后才能获取音频。账号密码只用于向官网登录，" +
                        "不会保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号 / 手机 / 邮箱") },
                    singleLine = true,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (captchaRequired) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = captcha,
                            onValueChange = { captcha = it },
                            label = { Text("验证码") },
                            singleLine = true,
                            enabled = !loading,
                            modifier = Modifier.weight(1f)
                        )
                        if (captchaImage != null) {
                            Image(
                                bitmap = captchaImage!!,
                                contentDescription = "验证码，点按换一张",
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable { refreshCaptcha() }
                            )
                        } else {
                            TextButton(onClick = { refreshCaptcha() }, enabled = !loading) {
                                Text("获取验证码", color = AppColors.Red)
                            }
                        }
                    }
                    if (captchaImage != null) {
                        Text(
                            "看不清？点图片换一张",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.Muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.RedDeep
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.Red
                    )
                } else {
                    Text("登录", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("取消", color = AppColors.Muted)
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        containerColor = AppColors.Card,
        titleContentColor = AppColors.Ink,
        textContentColor = AppColors.Body
    )
}

private fun ByteArray.toImageBitmap(): ImageBitmap? =
    runCatching {
        BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
    }.getOrNull()
