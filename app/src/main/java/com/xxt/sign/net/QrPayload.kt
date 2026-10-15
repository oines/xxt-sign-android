package com.xxt.sign.net

/** 扫码 / 粘贴得到的一串二维码内容解析结果 */
data class QrPayload(
    val raw: String,
    val aid: String?,
    val enc: String?,
    val code: String?
) {
    val usable: Boolean get() = !enc.isNullOrEmpty() || !aid.isNullOrEmpty()
}

object QrParser {

    private const val KEY_AID = "aid"

    /**
     * 兼容以下几种二维码内容：
     *  1. SIGNIN:aid=123&source=15&Code=abcd&enc=XXXX
     *  2. https://mobilelearn.chaoxing.com/...?enc=XXXX&activeId=123
     *  3. env=...&aid=... 之类的裸 query
     *  4. 纯 enc 串
     */
    fun parse(input: String): QrPayload {
        val raw = input.trim()
        var aid: String? = null
        var enc: String? = null
        var code: String? = null

        val lower = raw.lowercase()
        when {
            lower.startsWith("signin:") -> {
                parseKv(raw.substringAfter(':'), { k, v ->
                    when (k) {
                        KEY_AID -> aid = v
                        "enc" -> enc = v
                        "code" -> code = v
                    }
                })
            }

            raw.contains('=') -> {
                parseKv(raw.substringAfter('?', raw), { k, v ->
                    when (k) {
                        KEY_AID, "activeid", "activeprimaryid", "id" -> if (aid == null) aid = v
                        "enc" -> enc = v
                        "code" -> code = v
                    }
                })
            }
        }

        // 兜底：整串当作 enc
        if (enc.isNullOrEmpty() && !raw.contains('=') && !raw.contains(':') && raw.length >= 8) {
            enc = raw
        }

        fun norm(v: String?): String? = v?.takeIf { it.isNotBlank() }?.let { if (it.contains('%')) dec(it) else it }

        return QrPayload(raw = raw, aid = norm(aid), enc = norm(enc), code = norm(code))
    }

    private inline fun parseKv(query: String, onPair: (String, String) -> Unit) {
        query.split('&').forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) {
                val k = kv.substring(0, i).trim().lowercase()
                val v = kv.substring(i + 1).trim()
                if (v.isNotEmpty()) onPair(k, v)
            }
        }
    }
}
