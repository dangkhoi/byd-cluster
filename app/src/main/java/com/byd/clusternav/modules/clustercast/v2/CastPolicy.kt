package com.byd.clusternav.modules.clustercast.v2

data class TargetEvidence(
    val projectionComponent: Boolean?,
    val connectedPhoneSession: Boolean?,
    val userProtected: Boolean,
)

object CastPolicy {
    fun classify(evidence: TargetEvidence): TargetClass = when {
        evidence.projectionComponent == null -> TargetClass.UNKNOWN_PROTECTED
        evidence.projectionComponent && evidence.connectedPhoneSession == true -> TargetClass.PROJECTION_SINK
        evidence.userProtected -> TargetClass.KEEP_SESSION
        evidence.projectionComponent -> TargetClass.UNKNOWN_PROTECTED
        else -> TargetClass.NORMAL
    }

    fun mayForceStop(targetClass: TargetClass): Boolean = targetClass == TargetClass.NORMAL
    fun preservesSession(targetClass: TargetClass): Boolean = targetClass != TargetClass.NORMAL
}
