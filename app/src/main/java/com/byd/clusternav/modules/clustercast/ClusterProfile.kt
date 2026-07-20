package com.byd.clusternav.modules.clustercast

import android.content.Context

/**
 * HỒ SƠ CỤM THEO MODEL XE (R8) — tách phần phụ-thuộc-xe ra sau 1 lớp, resolve = auto-detect trước, override sau.
 * Đa-model BYD (Seal · SL6 · Han · Tang…) cùng DiLink3 + XDJA container → recipe Seal-like, biến thiên chính = kích cụm.
 *
 *  • [castSeq]     = chuỗi lệnh AutoContainer bật chiếu. Seal DL3 = [30,16,35] (30=cong giữ km/h, 16=chiếu, 35=DI40).
 *  • [teardownSeq] = chuỗi lệnh tắt chiếu. Seal = [18,0] (18=đóng chiếu, 0=refresh video).
 *  • [vdNameHint]  = tên VD cụm để dò (chứa "xdja"/"fission").
 *
 * Serialize (export/import share nhóm): "id;diLink;W;H;cast(-nối);tear(-nối);vdNameHint".
 * Phần companion PURE (parse/export/detectSeed) không đụng Context → unit-test off-device được; [resolve] mới cần ctx.
 */
data class ClusterProfile(
    val id: String,
    val diLink: Int,
    val clusterW: Int,
    val clusterH: Int,
    val castSeq: List<Int>,
    val teardownSeq: List<Int>,
    val vdNameHint: String,
) {
    /** Chuỗi text để XUẤT (share nhóm) / lưu override. Round-trip qua [parse]. */
    fun export(): String = listOf(
        id, diLink.toString(), clusterW.toString(), clusterH.toString(),
        castSeq.joinToString("-"), teardownSeq.joinToString("-"), vdNameHint
    ).joinToString(";")

    /** Mô tả ngắn cho UI. */
    fun summary(): String =
        "$id · DL$diLink · ${clusterW}×$clusterH · chiếu[${castSeq.joinToString(",")}] · tắt[${teardownSeq.joinToString(",")}]"

    companion object {
        // ★ SEED đã VERIFY trên xe (2026-07-19): Seal DL3, VD XDJA/fission, chiếu 30→16→35, tắt 18→0.
        //   Tái tạo CHÍNH XÁC sequence hiện tại (behavior-preserving). clusterW/H fallback 1920×720 (auto-detect đè khi có VD thật).
        val SEAL_DL3 = ClusterProfile(
            id = "seal_dl3", diLink = 3, clusterW = 1920, clusterH = 720,
            castSeq = listOf(30, 16, 35), teardownSeq = listOf(18, 0), vdNameHint = "xdja"
        )

        // Model BYD lạ chưa verify: cùng DiLink3 + XDJA (de-risk Q5) → recipe Seal-like, dò xdja/fission.
        val GENERIC_FALLBACK = ClusterProfile(
            id = "generic_dl3", diLink = 3, clusterW = 1920, clusterH = 720,
            castSeq = listOf(30, 16, 35), teardownSeq = listOf(18, 0), vdNameHint = "fission"
        )

        private const val PREF = "clustercast"
        private const val KEY_OVERRIDE = "profileOverride"

        /** parse chuỗi export → ClusterProfile. null nếu hỏng (dùng cho import + load override).
         *  VALIDATE (R-hardening): chuỗi share trong nhóm là UNTRUSTED → ép W/H trong (0,8192], diLink 1..9,
         *  mã cast/teardown trong [0,255] (chống nạp mã `service call AutoContainer` tùy ý/âm + bounds suy biến vỡ resize). */
        fun parse(s: String): ClusterProfile? {
            val f = s.split(";")
            if (f.size != 7) return null
            val id = f[0].trim()
            if (id.isEmpty() || id.length > 32) return null
            val diLink = f[1].trim().toIntOrNull() ?: return null
            if (diLink !in 1..9) return null
            val w = f[2].trim().toIntOrNull() ?: return null
            val h = f[3].trim().toIntOrNull() ?: return null
            if (w !in 1..8192 || h !in 1..8192) return null
            val cast = parseSeq(f[4]) ?: return null
            val tear = parseSeq(f[5]) ?: return null
            val hint = f[6].trim()
            return ClusterProfile(id, diLink, w, h, cast, tear, hint)
        }

        /** "30-16-35" → [30,16,35]. Rỗng → []. null nếu có phần không phải số / ngoài dải [0,255] / quá dài (>16). */
        private fun parseSeq(s: String): List<Int>? {
            if (s.isBlank()) return emptyList()
            val parts = s.split("-")
            if (parts.size > 16) return null
            val out = ArrayList<Int>(parts.size)
            for (p in parts) {
                val n = p.trim().toIntOrNull() ?: return null
                if (n !in 0..255) return null
                out.add(n)
            }
            return out
        }

        /**
         * Detect SEED từ Build + getprop (PURE, offline). Fleet nhóm = BYD DL3 XDJA → [SEAL_DL3]; khác → [GENERIC_FALLBACK].
         * (Mọi head-unit BYD báo Build.MODEL = "BYD AUTO" → nhận diện bằng chuỗi "byd".)
         */
        fun detectSeed(model: String, brand: String, manufacturer: String, extraProps: String): ClusterProfile {
            val hay = "$model $brand $manufacturer $extraProps".lowercase()
            return if (hay.contains("byd")) SEAL_DL3 else GENERIC_FALLBACK
        }

        /** resolve profile: user-override (prefs) ưu tiên; else detect từ Build.MODEL + getprop; else GENERIC_FALLBACK. */
        fun resolve(ctx: Context): ClusterProfile {
            loadOverride(ctx)?.let { return it }
            return detectSeed(
                android.os.Build.MODEL ?: "",
                android.os.Build.BRAND ?: "",
                android.os.Build.MANUFACTURER ?: "",
                getProp("ro.product.model") + " " + getProp("ro.product.name")
            )
        }

        fun loadOverride(ctx: Context): ClusterProfile? {
            val s = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_OVERRIDE, "") ?: ""
            return if (s.isBlank()) null else parse(s)
        }

        fun saveOverride(ctx: Context, p: ClusterProfile) {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_OVERRIDE, p.export()).apply()
        }

        fun clearOverride(ctx: Context) {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_OVERRIDE).apply()
        }

        /** getprop in-proc qua android.os.SystemProperties (reflection; hidden nhưng đọc OK no-root). "" nếu lỗi/off-device. */
        private fun getProp(key: String): String = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            (c.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
        }.getOrDefault("")
    }
}
