package com.xxt.sign.net

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 学习通 passport 的密码/账号加密。
 *
 * 依据 passport2 官方登录页的登陆逻辑：当表单 t=true 时，
 * uname 与 password 都会先用同一把钥匙做 AES/CBC/PKCS7，再取密文的标准 Base64。
 * 钥匙与 IV 相同，为 16 字节字符串。
 *
 * 页面里还留着一个老的 DES/ECB 函数，但当前登录流程已经不走它了。
 */
object CxCrypto {

    /** AES 密钥，同时也是 IV */
    private const val KEY = "u2oh6Vu^HWe4_AES"

    fun aesEncryptBase64(plain: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes)
        )
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
}
