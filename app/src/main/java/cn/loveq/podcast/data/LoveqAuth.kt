package cn.loveq.podcast.data

import org.json.JSONObject

/**
 * 登录结果。`CaptchaRequired` 里带的是官网验证码图片的原始字节，
 * 由界面解码显示；图片加载失败时 `image` 为 null，用户仍可手输。
 */
sealed interface LoginOutcome {

    data class Success(val username: String) : LoginOutcome

    data class CaptchaRequired(
        val image: ByteArray?,
        val message: String
    ) : LoginOutcome

    data class Failure(val message: String) : LoginOutcome
}

/**
 * 官网 `user.php?act=signin` 的 JSON 解析。
 *
 * 官网前端（`user.js` 的 `signIn_callback`）用的就是这三个字段：
 * `error == 0` 成功，`captcha == 1` 要验证码，`message` 给人看。
 * 解析单独抽出来，避免把网络、Cookie 和 JSON 细节搅在一起，也方便单测。
 */
internal data class LoginReply(
    val error: Int,
    val message: String,
    val captcha: Boolean
) {
    companion object {
        fun parse(body: String): LoginReply {
            val json = JSONObject(body)
            return LoginReply(
                error = json.optInt("error", -1),
                message = json.optString("message").trim(),
                captcha = json.optInt("captcha", 0) == 1
            )
        }
    }
}
