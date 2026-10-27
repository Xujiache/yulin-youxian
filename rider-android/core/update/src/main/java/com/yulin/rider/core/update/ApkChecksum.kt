package com.yulin.rider.core.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

object ApkChecksum {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun matches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank() || !file.isFile) return false
        val normalized = expected.replace(":", "").replace(" ", "").lowercase(Locale.ROOT)
        return sha256(file) == normalized
    }
}
