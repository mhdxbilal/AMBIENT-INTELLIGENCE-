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

    fun toggleRule(ruleId: String) {
        bridge.toggleRule(ruleId)
    }

    fun triggerAutomation(ruleId: String): String {
        return bridge.triggerLocalAutomation(ruleId)
    }
}
