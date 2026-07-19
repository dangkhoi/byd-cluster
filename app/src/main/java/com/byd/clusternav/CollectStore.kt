package com.byd.clusternav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Kho THU THẬP cho hội: (1) icon mũi tên large-icon GMaps (mỗi loại 1 lần) để dựng TEMPLATE matching
 * — cần vì small-icon của ReVanced GMaps luôn là logo, hướng rẽ chỉ ở large-icon; (2) bảng HUD feature-id.
 * Lưu vào /sdcard/Download/ClusterNav-collect (dễ gửi/pull) nếu có quyền; không thì app-files.
 */
object CollectStore {
    private const val TAG = "Collect"
    private val seen = HashSet<Int>()

    fun dir(ctx: Context): File {
        val canPub = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val d = if (canPub)
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClusterNav-collect")
        else
            File(ctx.getExternalFilesDir(null), "collect")
        d.mkdirs()
        return d
    }

    /** Lưu icon mũi tên nếu CHƯA gặp (dedup theo hash). Kèm tên đường vào labels.csv. */
    fun saveIconIfNew(ctx: Context, bmp: Bitmap?, road: String): Boolean {
        bmp ?: return false
        val hash = quickHash(bmp)
        synchronized(seen) { if (!seen.add(hash)) return false }
        return runCatching {
            val d = dir(ctx)
            val key = Integer.toHexString(hash)
            FileOutputStream(File(d, "icon_$key.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(d, "labels.csv").appendText("$key,\"${road.replace("\"", " ").replace(",", " ")}\"\n")
            Log.i(TAG, "saved icon_$key.png road='$road'")
            true
        }.getOrElse { Log.e(TAG, "saveIcon fail", it); false }
    }

    fun saveText(ctx: Context, name: String, text: String) {
        runCatching { File(dir(ctx), name).writeText(text) }.onFailure { Log.e(TAG, "saveText fail", it) }
    }

    fun iconCount(ctx: Context): Int =
        runCatching { dir(ctx).listFiles { f -> f.name.endsWith(".png") }?.size ?: 0 }.getOrDefault(0)

    fun pathHint(ctx: Context): String = dir(ctx).absolutePath

    private fun quickHash(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.getOrElse { return 0 }
        var hsh = w * 31 + h
        var i = 0
        while (i < px.size) { hsh = hsh * 31 + px[i]; i += 5 }
        return hsh
    }
}
