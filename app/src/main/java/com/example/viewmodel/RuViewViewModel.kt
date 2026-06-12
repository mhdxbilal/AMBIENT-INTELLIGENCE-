package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.model.AutomationRule
import com.example.model.NetworkNode
import com.example.model.Room
import com.example.service.MockWiFiSensingMatterBridge
import kotlinx.coroutines.flow.StateFlow

class RuViewViewModel : ViewModel() {
    private val bridge = MockWiFiSensingMatterBridge()

    val rooms: StateFlow<List<Room>> = bridge.rooms
    val rules: StateFlow<List<AutomationRule>> = bridge.rules
    val nodes: StateFlow<List<NetworkNode>> = bridge.nodes
    val diagnosticEvents: StateFlow<List<String>> = bridge.diagnosticEvents

    fun toggleRule(ruleId: String) {
        bridge.toggleRule(ruleId)
    }

    fun updateNodeSensitivity(nodeId: String, sensitivity: Float) {
        bridge.updateNodeSensitivity(nodeId, sensitivity)
    }

    fun updateRoomSensitivity(roomId: String, sensitivity: Float) {
        bridge.updateRoomSensitivity(roomId, sensitivity)
    }

    fun triggerAutomation(ruleId: String): String {
        return bridge.triggerLocalAutomation(ruleId)
    }
}
