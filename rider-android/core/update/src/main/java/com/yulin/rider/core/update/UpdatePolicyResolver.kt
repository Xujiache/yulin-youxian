package com.yulin.rider.core.update

object UpdatePolicyResolver {
    const val NONE = "NONE"
    const val OPTIONAL = "OPTIONAL"
    const val FORCE = "FORCE"

    fun resolve(
        clientVersionCode: Int,
        latestVersionCode: Int?,
        releasePolicy: String?,
        minSupportedVersionCode: Int?,
    ): String {
        val latest = latestVersionCode ?: return NONE
        if (clientVersionCode >= latest) return NONE
        val minSupported = minSupportedVersionCode ?: 0
        if (FORCE.equals(releasePolicy, ignoreCase = true) || clientVersionCode < minSupported) {
            return FORCE
        }
        return OPTIONAL
    }
}
