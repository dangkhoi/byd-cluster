package com.byd.clusternav.cast.platform

import com.byd.clusternav.modules.clustercast.v2.*

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Hiện thực Android của port [AtomicBytes].
 *
 * Tách ra khỏi CastSessionStore ngày 2026-07-27: phần quyết định và codec của store là thuần và đã
 * sang :core, còn việc ghi file nguyên tử là việc của nền tảng nên ở lại phía biết-thiết-bị. Nhờ port
 * này mà :core không cần biết dữ liệu được ghi bằng gì — CLI runner dùng file thường, test dùng bộ nhớ.
 */
class AndroidAtomicBytes(file: File) : AtomicBytes {
    private val atomic = AtomicFile(file)
    private val backup = File(atomic.baseFile.path + ".bak")

    /** AtomicFile recovers from its backup, so a pending rename is not an empty store. */
    override fun exists(): Boolean = atomic.baseFile.exists() || backup.exists()
    override fun read(): ByteArray = atomic.readFully()

    override fun write(bytes: ByteArray) {
        atomic.baseFile.parentFile?.mkdirs()
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: IOException) {
            stream?.let(atomic::failWrite)
            throw failure
        } catch (failure: RuntimeException) {
            stream?.let(atomic::failWrite)
            throw failure
        }
    }
}
