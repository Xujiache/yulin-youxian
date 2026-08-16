package com.yulin.rider.core.update

object UpdatePromptPolicy {
    fun shouldShowOptionalDialog(
        onDuty: Boolean,
        policy: String,
        showOptional: Boolean,
    ): Boolean {
        if (onDuty) return false
        if (policy != UpdatePolicyResolver.OPTIONAL) return false
        return showOptional
    }
}
