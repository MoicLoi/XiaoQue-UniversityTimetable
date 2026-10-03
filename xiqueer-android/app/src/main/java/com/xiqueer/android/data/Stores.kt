package com.xiqueer.android.data

import android.content.Context
import com.xiqueer.protocol.XqUser
import java.io.File

/**
 * 凭据与会话。
 *
 * 分两层存:
 * - **机密**(token / jwt / md5)走 [SecureStore],与 Keystore 绑定;
 * - **标识符**(username / xxdm / xm / usertype / uuid)走普通 SharedPreferences ——
 *   它们不是凭据(学号印在学生证上),而且启动路径上要用它们做登录页预填,
 *   没必要为它们付解密成本。
 *
 * **这里不存密码。** 历史版本存过(`password` 键),但全项目从来没有读取点 ——
 * 只写不读的凭据就是纯负债,已删除;老装机升上来时在 [init] 里清一次。
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    init {
        // 一次性清理:老版本把登录密码明文写在这个键里
        if (prefs.contains(KEY_LEGACY_PASSWORD)) {
            prefs.edit().remove(KEY_LEGACY_PASSWORD).apply()
        }
        // 老版本把 token/jwt/md5 明文存在这里,升级后搬到加密存储并抹掉明文
        migrateToSecure()
    }

    private fun migrateToSecure() {
        for (k in SECURE_KEYS) {
            val plain = prefs.getString(k, null) ?: continue
            if (plain.isNotEmpty()) secure.put(k, plain)
            prefs.edit().remove(k).apply()
        }
    }

    var username: String
        get() = prefs.getString("username", "").orEmpty()
        set(v) = prefs.edit().putString("username", v).apply()

    /**
     * 学校代码。**默认空** —— 不预设任何学校。
     *
     * 早先这里默认 `10162`(开发时用的那所),后果是:一个全新用户装完 App,
     * 登录页虽然显示"选择学校",但底层 xxdm 已经是辽宁中医药大学了 ——
     * 别的学校的学生会莫名其妙被登到错误的学校去。
     * 学校必须由用户显式选一次。
     */
    var xxdm: String
        get() = prefs.getString("xxdm", "").orEmpty()
        set(v) = prefs.edit().putString("xxdm", v).apply()

    /**
     * 学校名。登录页显示的是「学校 辽宁中医药大学」这样的名字,
     * 而不是 `xxdm` 那串数字 —— 用户不该被迫记住学校代码。
     */
    var xxmc: String
        get() = prefs.getString("xxmc", "").orEmpty()
        set(v) = prefs.edit().putString("xxmc", v).apply()

    private fun secret(k: String): String = secure.get(k).orEmpty()

    val user: XqUser
        get() = XqUser(
            userid = prefs.getString("userid", "").orEmpty(),
            uuid = prefs.getString("uuid", "").orEmpty(),
            usertype = prefs.getString("usertype", "STU").orEmpty(),
            xm = prefs.getString("xm", "").orEmpty(),
            xxdm = xxdm,
            // 这三项是机密,从加密存储读
            md5 = secret("md5"),
            token = secret("token").ifEmpty { "00000" },
            jwt = secret("jwt"),
        )

    val hasSession: Boolean
        get() = user.token.isNotEmpty() && user.token != "00000" && user.userid.isNotEmpty()

    fun saveUser(u: XqUser) {
        prefs.edit()
            .putString("userid", u.userid)
            .putString("uuid", u.uuid)
            .putString("usertype", u.usertype)
            .putString("xm", u.xm)
            .putString("xxdm", u.xxdm)
            .apply()
        secure.put("md5", u.md5)
        secure.put("token", u.token)
        secure.put("jwt", u.jwt)
    }

    fun clearSession() {
        prefs.edit()
            .remove("userid").remove("uuid").remove("usertype").remove("xm")
            .apply()
        // 机密只走加密存储,登出时一并抹掉
        SECURE_KEYS.forEach { secure.remove(it) }
    }

    companion object {
        /** 老版本用它当默认学校代码。**现在不再使用** —— 学校必须用户自己选。 */
        const val DEFAULT_XXDM = "10162"

        /** 老版本存明文密码用的键,只为清理而保留这个名字。 */
        private const val KEY_LEGACY_PASSWORD = "password"

        /** 需要加密保存的会话字段。 */
        private val SECURE_KEYS = listOf("md5", "token", "jwt")
    }
}

/**
 * 原始 JSON 缓存。UI 首帧只读这里,**永不阻塞网络**(P3 启动优化的前提)。
 * 键用动作名即可,P1 不做淘汰策略。
 */
class CacheStore(context: Context) {

    private val dir = File(context.filesDir, "cache").apply { mkdirs() }

    fun read(key: String): String? {
        val f = File(dir, "$key.json")
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun write(key: String, json: String) {
        runCatching { File(dir, "$key.json").writeText(json) }
    }

    fun savedAt(key: String): Long = File(dir, "$key.json").let { if (it.exists()) it.lastModified() else 0L }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
