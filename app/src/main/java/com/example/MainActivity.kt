package com.example

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import android.graphics.Paint

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider

enum class AppState { SPLASH, MAIN_DASHBOARD }
enum class SelectedArea { ALL_AREAS, LIVING_ROOM, BEDROOM, KITCHEN }
enum class RuViewState { PRESENCE_DETECTED, MULTI_ROOM_TRANSITION, NO_MOVEMENT, SLEEPING, ANOMALY_FALL_DETECTED }
enum class DeviceType { LIGHT, THERMOSTAT, PLUG, SMART_TV, SIREN, SENSING_NODE }
enum class FurnitureType { WALL, BENCH, TABLE, CHAIR, BED }

@Entity(tableName = "discovered_devices")
data class DiscoveredDevice(
    @PrimaryKey val id: String,
    val name: String,
    val type: DeviceType,
    val ipAddress: String,
    val xGrid: Float,
    val yGrid: Float,
    val area: SelectedArea,
    val signalStrength: Int
)

data class LiveTrackerPerson(val id: String, val name: String, val xCurrent: Float, val yCurrent: Float, val isMoving: Boolean, val breathingRate: Int, val heartRate: Int, val postureState: String)
data class AutomationRule(val id: String, val triggerState: RuViewState, val targetDevice: String, val actionValue: String, var isActive: Boolean)

@Entity(tableName = "static_furniture")
data class StaticFurnitureMap(
    @PrimaryKey val id: String,
    val type: FurnitureType,
    val xGrid: Float,
    val yGrid: Float,
    val width: Float,
    val height: Float,
    val confidencePercentage: Int
)

@Dao
interface TrackingDao {
    @Query("SELECT * FROM discovered_devices")
    fun getAllDevices(): Flow<List<DiscoveredDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevices(devices: List<DiscoveredDevice>)

    @Query("SELECT * FROM static_furniture")
    fun getAllFurniture(): Flow<List<StaticFurnitureMap>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFurniture(furniture: List<StaticFurnitureMap>)
}

class Converters {
    @androidx.room.TypeConverter
    fun fromDeviceType(value: DeviceType): String = value.name
    @androidx.room.TypeConverter
    fun toDeviceType(value: String): DeviceType = enumValueOf(value)

    @androidx.room.TypeConverter
    fun fromSelectedArea(value: SelectedArea): String = value.name
    @androidx.room.TypeConverter
    fun toSelectedArea(value: String): SelectedArea = enumValueOf(value)

    @androidx.room.TypeConverter
    fun fromFurnitureType(value: FurnitureType): String = value.name
    @androidx.room.TypeConverter
    fun toFurnitureType(value: String): FurnitureType = enumValueOf(value)
}

@Database(entities = [DiscoveredDevice::class, StaticFurnitureMap::class], version = 1, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao
}

data class LiveSensingData(val currentGlobalState: RuViewState, val peopleList: List<LiveTrackerPerson>, val discoveredDevices: List<DiscoveredDevice>, val dynamicFurnitureList: List<StaticFurnitureMap>, val isLidarScanning: Boolean, val lidarProgress: Float)

class SmartHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(application, AppDatabase::class.java, "ru_view_db").build()
    private val trackingDao = db.trackingDao()

    val currentAppState = MutableStateFlow(AppState.SPLASH)
    val currentTab = MutableStateFlow(0)
    val currentViewedArea = MutableStateFlow(SelectedArea.ALL_AREAS)
    
    val zoomScale = MutableStateFlow(1.0f)
    val panX = MutableStateFlow(0f)
    val panY = MutableStateFlow(0f)
    val rotationAngle = MutableStateFlow(0f)

    private val _liveData = MutableStateFlow(
        LiveSensingData(RuViewState.PRESENCE_DETECTED, emptyList(), emptyList(), emptyList(), false, 0f)
    )
    val liveData: StateFlow<LiveSensingData> = _liveData.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    val automationRules = MutableStateFlow(listOf(
        AutomationRule("r1", RuViewState.ANOMALY_FALL_DETECTED, "All Sirens", "ON", true),
        AutomationRule("r2", RuViewState.PRESENCE_DETECTED, "Living Room Lights", "ON", true),
        AutomationRule("r3", RuViewState.SLEEPING, "Master Bedroom Thermostat", "68F", true)
    ))

    private var tickCount = 0
    private var p1TargetX = 400f
    private var p1TargetY = 300f
    private var p1X = 100f
    private var p1Y = 100f

    init {
        logToTerminal("[SYSTEM_INIT] MockWiFiSensingMatterBridge started...")
        initBaseEnvironment()
        
        viewModelScope.launch {
            trackingDao.getAllDevices().collect { devs ->
                _liveData.value = _liveData.value.copy(discoveredDevices = devs)
            }
        }
        viewModelScope.launch {
            trackingDao.getAllFurniture().collect { furns ->
                _liveData.value = _liveData.value.copy(dynamicFurnitureList = furns)
            }
        }
        
        viewModelScope.launch {
            while (true) {
                tickCount++
                updateEngine()
                delay(1000)
            }
        }
    }

    private fun initBaseEnvironment() {
        viewModelScope.launch {
            val walls = listOf(
                StaticFurnitureMap("w1", FurnitureType.WALL, 0f, 0f, 800f, 20f, 100),
                StaticFurnitureMap("w2", FurnitureType.WALL, 0f, 500f, 800f, 20f, 100),
                StaticFurnitureMap("w3", FurnitureType.WALL, 0f, 0f, 20f, 500f, 100),
                StaticFurnitureMap("b1", FurnitureType.BENCH, 200f, 200f, 200f, 100f, 85),
                StaticFurnitureMap("bt1", FurnitureType.BED, 600f, 200f, 150f, 250f, 90),
                StaticFurnitureMap("t1", FurnitureType.TABLE, 300f, 400f, 120f, 120f, 75)
            )
            val devices = listOf(
                DiscoveredDevice("d1", "Sensing Node Alpha", DeviceType.SENSING_NODE, "192.168.1.101", 50f, 50f, SelectedArea.LIVING_ROOM, -45),
                DiscoveredDevice("d2", "Smart Bulb", DeviceType.LIGHT, "192.168.1.102", 200f, 400f, SelectedArea.LIVING_ROOM, -55),
                DiscoveredDevice("d3", "Matter TV Plug", DeviceType.SMART_TV, "192.168.1.103", 500f, 100f, SelectedArea.LIVING_ROOM, -60)
            )
            trackingDao.insertFurniture(walls)
            trackingDao.insertDevices(devices)
        }
    }

    private fun updateEngine() {
        p1X += (p1TargetX - p1X) * 0.2f
        p1Y += (p1TargetY - p1Y) * 0.2f
        val currentX = p1X
        val currentY = p1Y
        if (Math.abs(p1TargetX - p1X) < 20f) {
            p1TargetX = Random.nextFloat() * 700f + 50f
            p1TargetY = Random.nextFloat() * 400f + 50f
            
            if (Random.nextFloat() > 0.7f) {
                val obs = StaticFurnitureMap("slam_${tickCount}", FurnitureType.CHAIR, p1TargetX - 50f, p1TargetY - 50f, 50f, 50f, Random.nextInt(50, 90))
                viewModelScope.launch { trackingDao.insertFurniture(listOf(obs)) }
                logToTerminal("[WiFi SLAM] Radio reflection distortion detected static object at X:${obs.xGrid.toInt()}, Y:${obs.yGrid.toInt()}")
            }
        }
        
        if (tickCount % 5 == 0) {
            val rttDistance = Math.sqrt((currentX * currentX + currentY * currentY).toDouble()).toFloat()
            logToTerminal("[WiFi RTT] Multilateration range computed: String ${rttDistance}mm, Acc: 1-2m.")
        }
        
        val isFallDetected = (tickCount % 20 in 1..3)
        val p1Posture = if (isFallDetected) "FALL_DETECTED" else "MOVING"
        val globalState = if (isFallDetected) RuViewState.ANOMALY_FALL_DETECTED else RuViewState.PRESENCE_DETECTED
        
        val p1 = LiveTrackerPerson("p1", "Person 1 (Moving)", p1X, p1Y, true, Random.nextInt(16, 22), Random.nextInt(85, 105), p1Posture)
        
        _liveData.value = _liveData.value.copy(
            currentGlobalState = globalState, peopleList = listOf(p1)
        )
        if (tickCount % 3 == 0) logToTerminal("[CSI STREAM] Processing Subcarrier Waves... State: $globalState")
    }

    fun startLidarInitialization() {
        if (_liveData.value.isLidarScanning) return
        viewModelScope.launch {
            _liveData.value = _liveData.value.copy(isLidarScanning = true, lidarProgress = 0f)
            logToTerminal("[LiDAR MESH] LiDAR Space mapping initialized...")
            for (i in 1..50) {
                delay(100)
                _liveData.value = _liveData.value.copy(lidarProgress = i / 50f)
            }
            logToTerminal("[LiDAR MESH] Generated perfect layout boundary vectors")
            _liveData.value = _liveData.value.copy(isLidarScanning = false, lidarProgress = 1f)
            val border1 = StaticFurnitureMap("lidar1", FurnitureType.WALL, -10f, -10f, 820f, 5f, 100)
            val border2 = StaticFurnitureMap("lidar2", FurnitureType.WALL, -10f, 520f, 820f, 5f, 100)
            trackingDao.insertFurniture(listOf(border1, border2))
        }
    }

    fun logToTerminal(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _terminalLogs.value = (listOf("[$time] $msg") + _terminalLogs.value).take(100)
    }

    fun setAppState(state: AppState) { currentAppState.value = state }
    fun changeTab(t: Int) { currentTab.value = t }
    fun setArea(a: SelectedArea) { currentViewedArea.value = a }
    fun adjustZoom(d: Float) { zoomScale.value = (zoomScale.value * d).coerceIn(0.1f, 10f) }
    fun resetViewport() {
        zoomScale.value = 1.0f
        panX.value = 0f
        panY.value = 0f
        rotationAngle.value = 0f
    }
    fun adjustPan(dx: Float, dy: Float) { panX.value += dx; panY.value += dy }
    fun adjustRotation(r: Float) { rotationAngle.value = (rotationAngle.value + r) % 360 }
    
    fun toggleRule(id: String) {
        automationRules.value = automationRules.value.map {
            if (it.id == id) it.copy(isActive = !it.isActive) else it
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        setContent {
            MyApplicationTheme {
                val vm = viewModel<SmartHomeViewModel>()
                val state by vm.currentAppState.collectAsState()
                
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (state == AppState.SPLASH) {
                        SplashScreen { vm.setAppState(AppState.MAIN_DASHBOARD) }
                    } else {
                        MainDashboardScreen(vm, toneGen)
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(4000); onDone() }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(100.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, contentDescription = "Logo", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("RuViewer", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Advanced Spatial Sensing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Created By : Muhammed Bilal C", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Using Google AI Studio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("E-Mail : mbc4294@gmail.com", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(vm: SmartHomeViewModel, toneGen: ToneGenerator) {
    val liveData by vm.liveData.collectAsState()
    val tab by vm.currentTab.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(liveData.currentGlobalState) {
        if (liveData.currentGlobalState == RuViewState.ANOMALY_FALL_DETECTED) {
            val attrCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.createAttributionContext("ru_viewer") else ctx
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (attrCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                attrCtx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
            vm.logToTerminal("[ALERT ENGINE] Executed Local Audio Emergency Chime & Haptics")
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 8.dp) {
                NavigationBarItem(icon = { Icon(Icons.Default.Map, null) }, label = { Text("3D Spatial") }, selected = tab == 0, onClick = { vm.changeTab(0) })
                NavigationBarItem(icon = { Icon(Icons.Default.RuleFolder, null) }, label = { Text("Automation") }, selected = tab == 1, onClick = { vm.changeTab(1) })
                NavigationBarItem(icon = { Icon(Icons.Default.BugReport, null) }, label = { Text("Diagnostics") }, selected = tab == 2, onClick = { vm.changeTab(2) })
            }
        }
    ) { p ->
        Box(modifier = Modifier.padding(p).fillMaxSize()) {
            when (tab) {
                0 -> SpatialTab(vm)
                1 -> AutomationTab(vm)
                2 -> DiagnosticsTab(vm)
            }
            
            AnimatedVisibility(
                visible = liveData.currentGlobalState == RuViewState.ANOMALY_FALL_DETECTED,
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), 
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    elevation = CardDefaults.elevatedCardElevation(12.dp)
                ) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                        Text("CRITICAL ALERT: FALL DETECTED", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun SpatialTab(vm: SmartHomeViewModel) {
    val live by vm.liveData.collectAsState()
    val area by vm.currentViewedArea.collectAsState()
    val z by vm.zoomScale.collectAsState()
    val px by vm.panX.collectAsState()
    val py by vm.panY.collectAsState()
    val rot by vm.rotationAngle.collectAsState()
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, rotation -> 
                vm.adjustZoom(zoom)
                vm.adjustPan(pan.x, pan.y)
                vm.adjustRotation(rotation)
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f + px
                val cy = size.height / 2f + py
                translate(cx, cy) {
                    val rotRad = rot * Math.PI / 180f
                    val rCos = cos(rotRad).toFloat()
                    val rSin = sin(rotRad).toFloat()
                    
                    val ca = cos(Math.PI / 6).toFloat()
                    val sa = sin(Math.PI / 6).toFloat()
                    
                    fun tIso(xGrid: Float, yGrid: Float): Offset {
                        val cntX = 400f; val cntY = 250f
                        val relX = xGrid - cntX
                        val relY = yGrid - cntY
                        val rx = relX * rCos - relY * rSin + cntX
                        val ry = relX * rSin + relY * rCos + cntY
                        return Offset((rx - ry) * ca * z, (rx + ry) * sa * z)
                    }

                    live.dynamicFurnitureList.forEach { o ->
                        val p1 = tIso(o.xGrid, o.yGrid)
                        val p2 = tIso(o.xGrid + o.width, o.yGrid)
                        val p3 = tIso(o.xGrid + o.width, o.yGrid + o.height)
                        val p4 = tIso(o.xGrid, o.yGrid + o.height)
                        val p = Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close() }
                        
                        when (o.type) {
                            FurnitureType.WALL -> drawPath(p, onSurface.copy(alpha = 0.8f))
                            FurnitureType.BENCH -> drawPath(p, secondaryColor.copy(alpha = 0.5f), style = Stroke(4f * z))
                            FurnitureType.TABLE -> drawPath(p, tertiaryColor.copy(alpha = 0.6f))
                            else -> drawPath(p, onSurface.copy(alpha = 0.3f))
                        }
                        
                        val src = if(o.id.startsWith("slam")) "SLAM" else if(o.id.startsWith("lidar")) "LiDAR" else "System"
                        drawContext.canvas.nativeCanvas.drawText("$src (${o.confidencePercentage}%)", p1.x, p1.y - 12f*z, Paint().apply { color = android.graphics.Color.GRAY; textSize = 22f * z })
                    }

                    live.discoveredDevices.forEach { d ->
                        val pos = tIso(d.xGrid, d.yGrid)
                        drawCircle(primaryColor.copy(0.2f), z * 24f, pos)
                        val p = Path().apply { moveTo(pos.x, pos.y - z*12f); lineTo(pos.x + z*10f, pos.y); lineTo(pos.x, pos.y + z*12f); lineTo(pos.x - z*10f, pos.y); close() }
                        drawPath(p, primaryColor)
                        drawContext.canvas.nativeCanvas.drawText("${d.name}", pos.x + 30f*z, pos.y + 10f*z, Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 28f * z })
                    }

                    live.peopleList.forEach { per ->
                        val p = tIso(per.xCurrent, per.yCurrent)
                        val fall = per.postureState == "FALL_DETECTED"
                        val c = if (fall) errorColor else primaryColor
                        drawCircle(c.copy(0.3f), z * 45f, p)
                        drawCircle(c, z * 18f, p)
                        
                        drawContext.canvas.nativeCanvas.apply {
                            drawRect(p.x - 170f*z, p.y - z*30f - 130f*z, p.x + 170f*z, p.y - z*30f + 40f*z, Paint().apply { color = android.graphics.Color.argb(200, 20,20,25) })
                            val pnt = Paint().apply { color = android.graphics.Color.WHITE; textSize = 28f * z; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                            val acc = Paint().apply { color = android.graphics.Color.CYAN; textSize = 26f * z; textAlign = Paint.Align.CENTER }
                            drawText("${per.name} (X:${per.xCurrent.toInt()} Y:${per.yCurrent.toInt()})", p.x, p.y - z*30f - 80f*z, pnt)
                            drawText("Posture: [${per.postureState}]", p.x, p.y - z*30f - 35f*z, pnt)
                            drawText("HR: ${per.heartRate} | BR: ${per.breathingRate}", p.x, p.y - z*30f + 15f*z, acc)
                        }
                    }
                }
            }
        }
        
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectedArea.values().forEach { a ->
                    FilterChip(
                        selected = a == area, 
                        onClick = { vm.setArea(a) }, 
                        label = { Text(a.name.replace("_", " "), fontWeight = FontWeight.Medium) }
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            shadowElevation = 8.dp
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.adjustZoom(1.5f) }) { Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { vm.adjustZoom(0.66f) }) { Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { vm.adjustRotation(-15f) }) { Text("↺", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { vm.adjustRotation(15f) }) { Text("↻", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { vm.resetViewport() }) { Text("R", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                FilledTonalButton(onClick = { vm.startLidarInitialization() }) { Text("LiDAR Scan") }
            }
        }

        if (live.isLidarScanning) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.6f)), Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(16.dp), modifier = Modifier.padding(32.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LiDAR Spatial Core Mapping...", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("[Zero Camera Data Saved - Processed Locally]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        LinearProgressIndicator(progress = { live.lidarProgress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AutomationTab(vm: SmartHomeViewModel) {
    val rules by vm.automationRules.collectAsState()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Text("Smart Automations", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(rules) { r ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Automated Trigger", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("IF ${r.triggerState}\nTHEN ${r.actionValue} -> ${r.targetDevice}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = r.isActive, onCheckedChange = { vm.toggleRule(r.id) })
                            OutlinedButton(onClick = {
                                Toast.makeText(ctx, "Matter RPC: COMMAND_${r.actionValue}", Toast.LENGTH_LONG).show()
                                vm.logToTerminal("[ACTION] Executed Local Transaction Packet -> ${r.targetDevice}")
                            }) {
                                Text("Test Packet")
                            }
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
    
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Text("Diagnostics & Privacy", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontWeight = FontWeight.Bold)
        
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Privacy Lock Active", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Raw WiFi CSI computed locally. Zero cameras, zero cloud.", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        Text("Telemetry Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(live.discoveredDevices) { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.width(160.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(d.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("${d.signalStrength} dBm", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        Text("System Event Logs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                items(logs) { l -> 
                    Text(l, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp), lineHeight = 14.sp) 
                }
            }
        }
        Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}
