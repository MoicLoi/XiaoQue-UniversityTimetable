package com.xiqueer.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机机密存储(用户端)。
 *
 * 存什么:AI 的 API Key、会话 token / jwt / md5 —— 也就是"泄露了就能冒充你"的东西。
 * 不存什么:学号、学校代码、姓名。它们是**标识符不是凭据**(学号印在学生证上),
 * 而且启动路径要用它们做登录页预填,没必要付解密成本。
 *
 * 做法:密钥由 **Android Keystore** 生成并保管(AES-256-GCM,key 本身永不出硬件/系统),
 * 密文按 `base64(iv):base64(ciphertext)` 存进普通 SharedPreferences。
 *
 * 为什么不用 `androidx.security:security-crypto`:
 * - 那个库已被 Google 标记弃用,而且会带进 Tink + protobuf-lite(约 200 KB 与一批类);
 * - 这里需要的只是"平台给你一个 GCM 密钥 + 随机 IV"这一种模式,
 *   平台 API 本身就是给这个用途设计的,**不是自己造密码学**;
 * - 封版前夕不想再引一个弃用依赖,也不想去追它的兼容性 bug。
 *
 * ⚠️ 三条容易写错的地方,这里都处理了:
 * 1. **每次加密都换 IV** —— GCM 下 IV 复用是灾难性的;IV 随机生成并随密文一起存;
 * 2. **解密失败不许崩** —— 换机/恢复备份/改锁屏导致 Keystore 密钥失效是常见情况,
 *    统一当作"没有这个值"并清掉,而不是把用户挡在崩溃页;
 * 3. **惰性初始化** —— 密钥只在第一次读写时才碰 Keystore,不在 Application.onCreate 里。
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 只在第一次读写时访问 Keystore。
     *
     * 名字刻意不叫 `key` —— 下面几个函数的参数也叫 `key`(存储键),
     * 同名会把属性遮住,于是 `key ?: return` 里的 `key` 变成 String,
     * 编译器只会报一句含糊的 "None of the following candidates is applicable"。
     */
    private val aesKey: SecretKey? by lazy { loadOrCreateKey() }

    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        val sep = raw.indexOf(':')
        if (sep <= 0) {
            // 不是我们写的格式:直接丢掉,别尝试解释
            remove(key)
            return null
        }
        return try {
            val iv = Base64.decode(raw.substring(0, sep), Base64.NO_WRAP)
            val body = Base64.decode(raw.substring(sep + 1), Base64.NO_WRAP)
            val k = aesKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效 / 密文损坏:清掉这一项,当作没存过。
            // 不往上报错,更不崩 —— 用户最多是重新填一次 API Key。
            Log.w(TAG, "无法解出 $key,已清除该项", e)
            remove(key)
            null
        }
    }

    fun put(key: String, value: String) {
        val k = aesKey
        if (k == null) {
            Log.w(TAG, "Keystore 不可用,拒绝明文落盘:$key")
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            // 每次加密都新建 IV —— GCM 下绝不能复用
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(body, Base64.NO_WRAP)
            prefs.edit().putString(key, packed).apply()
        } catch (e: Exception) {
            Log.e(TAG, "加密 $key 失败", e)
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 只用来判断"这个键在不在",不涉及解密 —— UI 判断"有没有配 Key"走它。 */
    fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * 首次访问 Keystore 是这套方案唯一的可观成本,所以这里**把耗时打出来**。
     *
     * 一次进程只发生一次;打 INFO 级别是为了让"启动时到底付了多少"这个问题
     * 有实测答案,而不是靠猜。真要优化时先看这条日志,别凭感觉动代码。
     */
    private fun loadOrCreateKey(): SecretKey? {
        val t0 = System.nanoTime()
        val created: Boolean
        val key = try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                created = false
                existing.secretKey
            } else {
                created = true
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            // 不要求用户认证:这些值要在后台(如 Worker)也能读
                            .setUserAuthenticationRequired(false)
                            .build(),
                    )
                    generateKey()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keystore 初始化失败,机密将无法保存", e)
            return null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "Keystore ${if (created) "新建" else "载入"}密钥耗时 ${ms}ms")
        return key
    }

    private companion object {
        const val TAG = "XqSecure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xq_secure_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS = "secure"
    }
}
