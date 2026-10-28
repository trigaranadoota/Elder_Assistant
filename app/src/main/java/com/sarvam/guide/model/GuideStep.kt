package com.sarvam.guide.model

data class GuideStep(
    val step: Int,
    val targetX: Double,
    val targetY: Double,
    val what: String,
    val action: String,
    val done: Boolean,
    val risk: String,
    val screen: String,
    val onTask: Boolean,
    val recovery: String,
)

fun GuideStep.isRecovery(): Boolean = !onTask && recovery.isNotBlank()
