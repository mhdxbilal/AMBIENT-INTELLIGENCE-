package com.example.service

import com.example.model.AutomationRule
import com.example.model.NetworkNode
import com.example.model.NodeStatus
import com.example.model.Room
import com.example.model.SensingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class MockWiFiSensingMatterBridge {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    private val _nodes = MutableStateFlow<List<NetworkNode>>(emptyList())
    val nodes: StateFlow<List<NetworkNode>> = _nodes.asStateFlow()

    init {
        // Initialize rules
        _rules.value = listOf(
            AutomationRule(
                id = "rule_1",
                description = "IF Bedroom WiFi CSI shifts to 'someone-sleeping' THEN issue Matter command to dim smart lights to 0% and adjust WiFi thermostat to 21°C.",
                isEnabled = true
            ),
            AutomationRule(
                id = "rule_2",
                description = "IF Living Room WiFi CSI detects 'no-movement' for 15 minutes THEN send Matter command to power down TV and smart plugs.",
                isEnabled = false
            )
        )

        // Initialize network nodes
        _nodes.value = listOf(
            NetworkNode("node_1", "Main Router", "Main Router Nodes", NodeStatus.HEALTHY, "192.168.1.1", 100),
            NetworkNode("node_2", "Living Room Sensor", "ESP32 WiFi CSI Sub-Nodes", NodeStatus.HEALTHY, "192.168.1.42", 95),
            NetworkNode("node_3", "Bedroom Sensor", "ESP32 WiFi CSI Sub-Nodes", NodeStatus.DEGRADED, "192.168.1.43", 68),
            NetworkNode("node_4", "Kitchen Sensor", "ESP32 WiFi CSI Sub-Nodes", NodeStatus.HEALTHY, "192.168.1.44", 88)
        )

        // Simulate live updates
        scope.launch {
            while (true) {
                updateRoomData()
                delay(1500) // update every 1.5 seconds
            }
        }
    }

    private fun updateRoomData() {
        val currentRooms = _rooms.value.toMutableList()
        if (currentRooms.isEmpty()) {
            currentRooms.addAll(
                listOf(
                    Room("r1", "Living Room", SensingState.PRESENCE_DETECTED, 18, 72),
                    Room("r2", "Bedroom", SensingState.SLEEPING, 12, 55),
                    Room("r3", "Kitchen", SensingState.NO_MOVEMENT, null, null)
                )
            )
        } else {
            // Randomly fluctuate vitals and sometimes states
            for (i in currentRooms.indices) {
                val room = currentRooms[i]
                
                // Fluctuating vitals
                var newBr = room.breathingRate
                var newHr = room.heartRate
                
                if (room.state == SensingState.SLEEPING || room.state == SensingState.PRESENCE_DETECTED) {
                    newBr = ((room.breathingRate ?: 15) + Random.nextInt(-1, 2)).coerceIn(6, 30)
                    newHr = ((room.heartRate ?: 65) + Random.nextInt(-3, 4)).coerceIn(40, 120)
                }

                currentRooms[i] = room.copy(
                    breathingRate = newBr,
                    heartRate = newHr
                )
            }
        }
        _rooms.value = currentRooms
    }

    fun toggleRule(ruleId: String) {
        _rules.value = _rules.value.map {
            if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
        }
    }

    fun triggerLocalAutomation(ruleId: String): String {
        return "Dispatched local Matter-over-WiFi RPC packet for automation: \$ruleId"
    }
}
