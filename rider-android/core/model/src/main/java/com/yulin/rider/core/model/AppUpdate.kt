package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateLatest(
    val policy: String = "NONE",
    val channel: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val fileUrl: String? = null,
    val fileSize: Long? = null,
    val fileSha256: String? = null,
    val packageName: String? = null,
    val certSha256: String? = null,
    val minSupportedVersionCode: Int? = null,
) {
    val isForce: Boolean get() = policy.equals("FORCE", ignoreCase = true)
    val isOptional: Boolean get() = policy.equals("OPTIONAL", ignoreCase = true)
    val available: Boolean get() = isForce || isOptional
}
