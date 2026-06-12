package com.example.model

enum class SensingState(val displayName: String) {
    PRESENCE_DETECTED("Presence Detected"),
    TRANSITION("Multi-Room Transition"),
    NO_MOVEMENT("No Movement"),
    SLEEPING("Sleeping")
}

data class Room(
    val id: String,
    val name: String,
    val state: SensingState,
    val breathingRate: Int? = null, // 6-30
    val heartRate: Int? = null,      // 40-120
    val sensitivity: Float = 50f
)

data class AutomationRule(
    val id: String,
    val description: String,
    val isEnabled: Boolean,
    val devices: List<String> = emptyList()
)

enum class NodeStatus(val displayName: String) {
    HEALTHY("Healthy"), DEGRADED("Degraded"), OFFLINE("Offline")
}

data class NetworkNode(
    val id: String,
    val name: String,
    val type: String,
    val status: NodeStatus,
    val ipAddress: String,
    val signalIntegrity: Int, // 0-100%
    val sensitivity: Float = 50f // 0f - 100f
)
