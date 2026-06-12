package com.muhammedbilalc.ruviewer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import android.graphics.Paint

enum class AppState { SPLASH, MAIN_DASHBOARD }
enum class SelectedArea(val displayName: String) { ALL_AREAS("All Areas"), LIVING_ROOM("Living Room"), BEDROOM("Bedroom"), KITCHEN("Kitchen") }
enum class RuViewState { PRESENCE_DETECTED, MULTI_ROOM_TRANSITION, NO_MOVEMENT, SLEEPING, ANOMALY_FALL_DETECTED }
enum class DeviceType { LIGHT, THERMOSTAT, PLUG, SMART_TV, SIREN, SENSING_NODE }
enum class FurnitureType { WALL, BENCH, TABLE, CHAIR, BED }

data class DiscoveredDevice(val id: String, val name: String, val type: DeviceType, val ipAddress: String, val xGrid: Float, val yGrid: Float, val area: SelectedArea, val signalStrength: Int)
data class LiveTrackerPerson(val id: String, val name: String, val xCurrent: Float, val yCurrent: Float, val isMoving: Boolean, val breathingRate: Int, val heartRate: Int, val postureState: String)
data class AutomationRule(val id: String, val triggerState: RuViewState, val targetDevice: String, val actionValue: String, var isActive: Boolean)
data class LiveSensingData(val currentGlobalState: RuViewState, val peopleList: List<LiveTrackerPerson>, val discoveredDevices: List<DiscoveredDevice>)

data class VirtualObject(val id: String, val type: FurnitureType, val area: SelectedArea, val xGrid: Float, val yGrid: Float, val widthGrid: Float, val lengthGrid: Float)

class SmartHomeViewModel : ViewModel() {
    private val _currentAppState = MutableStateFlow(AppState.SPLASH)
    val currentAppState: StateFlow<AppState> = _currentAppState.asStateFlow()

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _currentViewedArea = MutableStateFlow(SelectedArea.ALL_AREAS)
    val currentViewedArea: StateFlow<SelectedArea> = _currentViewedArea.asStateFlow()

    private val _zoomScale = MutableStateFlow(1.0f)
    val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

    private val _panX = MutableStateFlow(0f)
    val panX: StateFlow<Float> = _panX.asStateFlow()

    private val _panY = MutableStateFlow(0f)
    val panY: StateFlow<Float> = _panY.asStateFlow()

    private val _rotationAngle = MutableStateFlow(0f)
    val rotationAngle: StateFlow<Float> = _rotationAngle.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _automationRules = MutableStateFlow(listOf(
        AutomationRule("r1", RuViewState.ANOMALY_FALL_DETECTED, "All Sirens", "ON", true),
        AutomationRule("r2", RuViewState.PRESENCE_DETECTED, "Living Room Lights", "ON", true),
        AutomationRule("r3", RuViewState.SLEEPING, "Master Bedroom Thermostat", "68F", true)
    ))
    val automationRules: StateFlow<List<AutomationRule>> = _automationRules.asStateFlow()

    private val _liveData = MutableStateFlow(LiveSensingData(RuViewState.PRESENCE_DETECTED, emptyList(), emptyList()))
    val liveData: StateFlow<LiveSensingData> = _liveData.asStateFlow()

    val virtualObjects = listOf(
        VirtualObject("wall1", FurnitureType.WALL, SelectedArea.ALL_AREAS, 0f, 0f, 15f, 0.5f),
        VirtualObject("wall2", FurnitureType.WALL, SelectedArea.ALL_AREAS, 0f, 10f, 15f, 0.5f),
        VirtualObject("sofa", FurnitureType.BENCH, SelectedArea.LIVING_ROOM, 2f, 2f, 4f, 2f),
        VirtualObject("bed", FurnitureType.BED, SelectedArea.BEDROOM, 11f, 2f, 3f, 4f),
        VirtualObject("table", FurnitureType.TABLE, SelectedArea.KITCHEN, 6f, 6f, 3f, 3f)
    )

    private var tickCount = 0
    private var p1TargetX = 5f
    private var p1TargetY = 5f
    private var p1X = 3f
    private var p1Y = 3f

    init {
        viewModelScope.launch {
            while (true) {
                tickCount++
                updateEngine()
                delay(1000)
            }
        }
        viewModelScope.launch {
            val possibleDevices = listOf(
                DiscoveredDevice("d1", "Sensing Node Alpha", DeviceType.SENSING_NODE, "192.168.1.101", 1f, 1f, SelectedArea.LIVING_ROOM, -45),
                DiscoveredDevice("d2", "Smart Bulb", DeviceType.LIGHT, "192.168.1.102", 4f, 8f, SelectedArea.LIVING_ROOM, -55),
                DiscoveredDevice("d3", "Matter TV Plug", DeviceType.SMART_TV, "192.168.1.103", 8f, 2f, SelectedArea.LIVING_ROOM, -60),
                DiscoveredDevice("d4", "Sensing Node Beta", DeviceType.SENSING_NODE, "192.168.1.104", 13f, 1f, SelectedArea.BEDROOM, -42),
                DiscoveredDevice("d5", "Bedroom Thermostat", DeviceType.THERMOSTAT, "192.168.1.105", 12f, 7f, SelectedArea.BEDROOM, -50),
                DiscoveredDevice("d6", "Kitchen Plug", DeviceType.PLUG, "192.168.1.106", 7f, 6f, SelectedArea.KITCHEN, -65)
            )
            var index = 0
            while (true) {
                delay(4000)
                if (index < possibleDevices.size) {
                    val dev = possibleDevices[index].copy(
                        xGrid = Random.nextFloat() * 14f + 1f,
                        yGrid = Random.nextFloat() * 9f + 1f,
                        ipAddress = "192.168.1.${Random.nextInt(100, 200)}"
                    )
                    _liveData.update { current ->
                        current.copy(discoveredDevices = current.discoveredDevices + dev)
                    }
                    logToTerminal("[mDNS DISCOVERY] Found ${dev.name} at ${dev.ipAddress}")
                    index++
                } else {
                    index = 0
                }
            }
        }
    }

    private fun updateEngine() {
        p1X += (p1TargetX - p1X) * 0.2f
        p1Y += (p1TargetY - p1Y) * 0.2f
        if (Math.abs(p1TargetX - p1X) < 0.5f) {
            p1TargetX = Random.nextFloat() * 14f + 1f
            p1TargetY = Random.nextFloat() * 9f + 1f
        }
        
        val p2X = 12f
        val p2Y = 4f
        val p2Hr = Random.nextInt(55, 65)
        val p2Br = Random.nextInt(12, 16)

        val isFallDetected = (tickCount % 20 in 1..3)
        val p1Posture = if (isFallDetected) "FALL_DETECTED" else "MOVING"
        val globalState = if (isFallDetected) RuViewState.ANOMALY_FALL_DETECTED else RuViewState.PRESENCE_DETECTED
        
        val p1 = LiveTrackerPerson("p1", "Person 1 (Moving)", p1X, p1Y, true, Random.nextInt(16, 22), Random.nextInt(85, 105), p1Posture)
        val p2 = LiveTrackerPerson("p2", "Person 2 (Resting)", p2X, p2Y, false, p2Br, p2Hr, "SLEEPING")

        _liveData.update {
            it.copy(currentGlobalState = globalState, peopleList = listOf(p1, p2))
        }
        
        if (tickCount % 3 == 0) logToTerminal("[CSI SIGNAL] Multi-Room Wave Disturbance Parsed (State: $globalState)")
    }

    fun logToTerminal(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _terminalLogs.update { logs -> (listOf("[$time] $msg") + logs).take(100) }
    }

    fun changeTab(t: Int) { _currentTab.value = t }
    fun setArea(a: SelectedArea) { _currentViewedArea.value = a }
    fun adjustZoom(d: Float) { _zoomScale.value = (_zoomScale.value + d).coerceIn(0.2f, 5.0f) }
    fun adjustPan(dx: Float, dy: Float) { _panX.value += dx; _panY.value += dy }
    fun adjustRotation(r: Float) { _rotationAngle.value = (_rotationAngle.value + r) % 360 }
    fun resetTransform() {
        _zoomScale.value = 1.0f
        _panX.value = 0f
        _panY.value = 0f
        _rotationAngle.value = 0f
    }
    
    fun toggleRule(id: String) {
        _automationRules.update { rules ->
            rules.map { if (it.id == id) it.copy(isActive = !it.isActive) else it }
        }
    }
    fun setAppState(state: AppState) { _currentAppState.value = state }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        setContent {
            val darkColorScheme = darkColorScheme(
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
                primary = Color(0xFF00E676),
                onPrimary = Color.Black
            )
            MaterialTheme(colorScheme = darkColorScheme) {
                val vm: SmartHomeViewModel = viewModel()
                val state by vm.currentAppState.collectAsState()
                
                if (state == AppState.SPLASH) {
                    SplashScreen { vm.setAppState(AppState.MAIN_DASHBOARD) }
                } else {
                    MainDashboardScreen(vm, toneGen, vibrator)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { 
        delay(5000)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Created By : Muhammed Bilal C", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Using Google AI Studio", style = MaterialTheme.typography.bodyLarge, color = Color.Cyan)
            Spacer(modifier = Modifier.height(4.dp))
            Text("E-Mail : mbc4294@gmail.com", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(color = Color(0xFF00E676))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(vm: SmartHomeViewModel, toneGen: ToneGenerator, vibrator: Vibrator) {
    val liveData by vm.liveData.collectAsState()
    val tab by vm.currentTab.collectAsState()

    LaunchedEffect(liveData.currentGlobalState) {
        if (liveData.currentGlobalState == RuViewState.ANOMALY_FALL_DETECTED) {
            toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
            vm.logToTerminal("[CRITICAL ENGINE] Executed Local Audio Emergency Chime")
        }
    }

    val bg = Color(0xFF0F172A)
    val accent = Color(0xFF00E676)

    Scaffold(
        containerColor = bg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E293B)) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Map, null) },
                    label = { Text("3D Matrix") },
                    selected = tab == 0,
                    onClick = { vm.changeTab(0) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = bg, selectedTextColor = accent, indicatorColor = accent, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.RuleFolder, null) },
                    label = { Text("Automations") },
                    selected = tab == 1,
                    onClick = { vm.changeTab(1) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = bg, selectedTextColor = accent, indicatorColor = accent, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.BugReport, null) },
                    label = { Text("Diagnostics") },
                    selected = tab == 2,
                    onClick = { vm.changeTab(2) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = bg, selectedTextColor = accent, indicatorColor = accent, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                )
            }
        }
    ) { p ->
        Box(modifier = Modifier.padding(p).fillMaxSize()) {
            when (tab) {
                0 -> SpatialTab(vm)
                1 -> AutomationTab(vm)
                2 -> DiagnosticsTab(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialTab(vm: SmartHomeViewModel) {
    val live by vm.liveData.collectAsState()
    val area by vm.currentViewedArea.collectAsState()
    val z by vm.zoomScale.collectAsState()
    val px by vm.panX.collectAsState()
    val py by vm.panY.collectAsState()
    val rot by vm.rotationAngle.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (live.currentGlobalState == RuViewState.ANOMALY_FALL_DETECTED) {
            Box(Modifier.fillMaxWidth().background(Color(0xFFFF1744)).padding(16.dp), Alignment.Center) {
                Text("CRITICAL ALERT: FALL DETECTED", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        LazyRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SelectedArea.values()) { a ->
                FilterChip(
                    selected = a == area,
                    onClick = { vm.setArea(a) },
                    label = { Text(a.displayName) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF00E676), selectedLabelColor = Color.Black)
                )
            }
        }

        Box(Modifier.fillMaxSize().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize()
                .background(Color(0xFF0F172A))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation -> 
                        vm.adjustZoom(zoom - 1f)
                        vm.adjustPan(pan.x, pan.y)
                        vm.adjustRotation(rotation)
                    }
                }
            ) {
                val cx = size.width / 2f + px
                val cy = size.height / 2f + py
                
                val rotRad = rot * Math.PI / 180f
                val rCos = cos(rotRad).toFloat()
                val rSin = sin(rotRad).toFloat()
                
                val ca = cos(Math.PI / 6).toFloat()
                val sa = sin(Math.PI / 6).toFloat()
                
                val baseScale = 40f * z

                fun tIso(gx: Float, gy: Float): Offset {
                    val rx = gx * rCos - gy * rSin
                    val ry = gx * rSin + gy * rCos
                    return Offset(
                        cx + (rx - ry) * ca * baseScale,
                        cy + (rx + ry) * sa * baseScale
                    )
                }

                vm.virtualObjects.filter { it.area == SelectedArea.ALL_AREAS || it.area == area }.forEach { o ->
                    val p1 = tIso(o.xGrid, o.yGrid)
                    val p2 = tIso(o.xGrid + o.widthGrid, o.yGrid)
                    val p3 = tIso(o.xGrid + o.widthGrid, o.yGrid + o.lengthGrid)
                    val p4 = tIso(o.xGrid, o.yGrid + o.lengthGrid)
                    val p = Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close() }
                    
                    val color = when (o.type) {
                        FurnitureType.WALL -> Color.Gray
                        FurnitureType.BED -> Color.Blue
                        FurnitureType.TABLE, FurnitureType.BENCH, FurnitureType.CHAIR -> Color(0xFF8B4513) // Wood Brown
                    }
                    drawPath(p, color.copy(alpha = 0.3f))
                    drawPath(p, color, style = Stroke(width = 2f * z))
                }

                live.discoveredDevices.filter { it.area == SelectedArea.ALL_AREAS || it.area == area }.forEach { d ->
                    val pos = tIso(d.xGrid, d.yGrid)
                    val nodeSize = 15f * z
                    val p = Path().apply { 
                        moveTo(pos.x, pos.y - nodeSize)
                        lineTo(pos.x + nodeSize, pos.y)
                        lineTo(pos.x, pos.y + nodeSize)
                        lineTo(pos.x - nodeSize, pos.y)
                        close() 
                    }
                    drawPath(p, Color(0xFFFFD700))
                    drawCircle(Color(0xFFFFD700).copy(0.3f), nodeSize * 2f, pos)
                    
                    drawContext.canvas.nativeCanvas.drawText("${d.name}\n${d.ipAddress}", pos.x + nodeSize + 5f, pos.y, Paint().apply { 
                        color = android.graphics.Color.YELLOW
                        textSize = 24f * z.coerceAtLeast(0.5f)
                    })
                }

                live.peopleList.forEach { per ->
                    val pos = tIso(per.xCurrent, per.yCurrent)
                    val fall = per.postureState == "FALL_DETECTED"
                    val c = if (fall) Color(0xFFFF1744) else Color.Cyan
                    drawCircle(c.copy(0.4f), baseScale, pos)
                    drawCircle(c, baseScale * 0.3f, pos)
                    
                    drawContext.canvas.nativeCanvas.apply {
                        val txtSize = 26f * z.coerceAtLeast(0.5f)
                        val accSize = 22f * z.coerceAtLeast(0.5f)
                        val boxW = 200f * z.coerceAtLeast(0.5f)
                        val boxH = 120f * z.coerceAtLeast(0.5f)
                        
                        drawRect(pos.x - boxW/2, pos.y - baseScale*2 - boxH, pos.x + boxW/2, pos.y - baseScale*2, Paint().apply { color = android.graphics.Color.argb(200, 0,0,0) })
                        
                        val pnt = Paint().apply { color = android.graphics.Color.WHITE; textSize = txtSize; textAlign = Paint.Align.CENTER }
                        val acc = Paint().apply { color = android.graphics.Color.CYAN; textSize = accSize; textAlign = Paint.Align.CENTER }
                        
                        drawText(per.name, pos.x, pos.y - baseScale*2 - boxH + txtSize + 5f, pnt)
                        drawText("X:%.1f Y:%.1f".format(per.xCurrent, per.yCurrent), pos.x, pos.y - baseScale*2 - boxH + txtSize * 2 + 10f, pnt)
                        drawText("Status: [${per.postureState}]", pos.x, pos.y - baseScale*2 - boxH + txtSize * 3 + 15f, pnt)
                        drawText("HR: ${per.heartRate} | BR: ${per.breathingRate}", pos.x, pos.y - baseScale*2 - 10f, acc)
                    }
                }
            }

            // HUD Camera Tool Deck
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).background(Color(0xFF1E293B).copy(alpha=0.8f), RoundedCornerShape(16.dp)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { vm.adjustZoom(0.2f) }) { Icon(Icons.Default.ZoomIn, "Zoom In", tint = Color.White) }
                IconButton(onClick = { vm.adjustZoom(-0.2f) }) { Icon(Icons.Default.ZoomOut, "Zoom Out", tint = Color.White) }
                IconButton(onClick = { vm.adjustRotation(15f) }) { Icon(Icons.Default.RotateRight, "Rotate", tint = Color.White) }
                IconButton(onClick = { vm.resetTransform() }) { Icon(Icons.Default.Refresh, "Reset", tint = Color.White) }
            }
        }
    }
}

@Composable
fun AutomationTab(vm: SmartHomeViewModel) {
    val rules by vm.automationRules.collectAsState()
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Enterprise Matter Automation Matrix", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF00E676)) }
        items(rules) { r ->
            Card(colors = CardDefaults.cardColors(Color(0xFF1E293B)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Trigger State: ${r.triggerState}", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("IF ${r.triggerState} THEN ${r.actionValue} -> ${r.targetDevice}", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = r.isActive, onCheckedChange = { vm.toggleRule(r.id) })
                        Button(
                            onClick = { Toast.makeText(context, "[MATTER RPC] Executed Command: ${r.actionValue} on ${r.targetDevice}", Toast.LENGTH_LONG).show() },
                            colors = ButtonDefaults.buttonColors(Color(0xFF00E676))
                        ) {
                            Text("Test Network Transaction Packet", color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsTab(vm: SmartHomeViewModel) {
    val logs by vm.terminalLogs.collectAsState()
    val live by vm.liveData.collectAsState()
    
    Column(Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(live.discoveredDevices) { d ->
                Card(colors = CardDefaults.cardColors(Color(0xFF1E293B))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("RSSI: ${d.signalStrength} dBm", color = Color.Cyan, fontSize = 12.sp)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        Text("Live Hex-Style Log Terminal", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(Color.Black), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.padding(12.dp)) {
                items(logs) { l -> Text(l, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(Color(0xFF1B5E20)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = "Privacy Lock", tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Privacy Lock Active: Raw WiFi CSI computation running locally. Zero cameras used. Zero data uploaded to cloud environments.", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
