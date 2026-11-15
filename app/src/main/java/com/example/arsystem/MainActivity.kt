package com.example.arsystem

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.arsystem.ui.theme.ArsystemTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private val Bg = com.example.arsystem.ui.theme.ObsidianBackground
private val SurfaceDark = com.example.arsystem.ui.theme.ObsidianSurface
private val SurfaceRaised = com.example.arsystem.ui.theme.ObsidianElevated
private val Outline = com.example.arsystem.ui.theme.ObsidianOutline
private val Primary = com.example.arsystem.ui.theme.SignalPrimary
private val Green = com.example.arsystem.ui.theme.SignalSuccess
private val Warning = com.example.arsystem.ui.theme.SignalWarning
private val Red = com.example.arsystem.ui.theme.SignalDanger
private val Muted = com.example.arsystem.ui.theme.SignalMuted

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { ArsystemTheme { OfflineApp(viewModel) } }
    }
}

@Composable
private fun OfflineApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = state.screen != AppScreen.LOGIN && state.screen != AppScreen.CATALOG) {
        when (state.screen) {
            // Registration and email verification are retained only for data
            // compatibility with older installs. The guest-first release has
            // no public registration path, so stale states return to login.
            AppScreen.VERIFY, AppScreen.REGISTER -> viewModel.openLogin()
            AppScreen.DETAIL, AppScreen.SCAN, AppScreen.HISTORY, AppScreen.PROFILE, AppScreen.ADMIN -> viewModel.openCatalog()
            AppScreen.RESULT -> viewModel.startInspection()
            else -> viewModel.openCatalog()
        }
    }
    Scaffold(
        // The app has its own bottom navigation surface. Lift transient action
        // messages above that surface so confirmations and errors stay readable.
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 86.dp)
            )
        },
        containerColor = Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        // Keep the app content below the status bar, while letting the custom
        // bottom navigation extend into the system navigation inset.
        Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            when (state.screen) {
                AppScreen.LOGIN -> LoginScreen(state, viewModel)
                AppScreen.REGISTER, AppScreen.VERIFY -> LoginScreen(state, viewModel)
                AppScreen.CATALOG -> CatalogScreen(state, viewModel)
                AppScreen.DETAIL -> EquipmentDetailScreen(state, viewModel)
                AppScreen.SCAN -> ScanScreen(state, viewModel)
                AppScreen.RESULT -> ResultScreen(state, viewModel)
                AppScreen.HISTORY -> HistoryScreen(state, viewModel)
                AppScreen.PROFILE -> ProfileScreen(state, viewModel)
                AppScreen.ADMIN -> AdminScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun VerifyScreen(state: ArUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("ПОДТВЕРЖДЕНИЕ ПОЧТЫ", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp)); Text("Проверьте рабочую почту", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val localMode = state.localVerificationCode != null && (state.statusMessage?.contains("локаль", ignoreCase = true) == true || state.statusMessage?.contains("SMTP", ignoreCase = true) == true)
        Text(if (localMode) "SMTP пока не настроен. Для демонстрации используйте код ниже." else "Шестизначный код отправлен на " + state.pendingEmail + ". После подтверждения кабинет будет доступен на устройстве.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        OtpFields(state.verificationCode, vm::updateVerificationCode)
        state.localVerificationCode?.let { Text(if (localMode) "Локальный demo-код: $it" else "Резервный код устройства: $it", color = Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        state.loginError?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp)); Button(onClick = vm::verifyRegistration, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("Подтвердить и создать кабинет", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp)); Text("Код нужен только один раз при регистрации. Дальше вход выполняется по почте и паролю.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun RegistrationScreen(state: ArUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ard_logo_screen), "Логотип AR DETECTOR", Modifier.size(76.dp).align(Alignment.CenterHorizontally), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(10.dp))
        Text("AR DETECTOR", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("Создать кабинет инженера", fontSize = 29.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Text("Подтверждение email потребуется только один раз", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        Surface(color = SurfaceRaised, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.name, vm::updateName, label = { Text("ФИО") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                var expanded by remember { mutableStateOf(false) }
                val positions = listOf("Инженер по эксплуатации", "Инженер-механик", "Инженер по КИПиА", "Специалист по охране труда")
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(state.position, modifier = Modifier.weight(1f), textAlign = TextAlign.Start) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { positions.forEach { role -> DropdownMenuItem(text = { Text(role) }, onClick = { vm.updatePosition(role); expanded = false }) } }
                }
                OutlinedTextField(state.email, vm::updateEmail, label = { Text("Рабочая почта") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.password, vm::updatePassword, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("Минимум 8 символов. После регистрации введите код из письма.", color = Muted, fontSize = 11.sp)
                state.loginError?.let { Text(it, color = Red, fontSize = 12.sp) }
                Button(onClick = vm::register, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp)) { Text("Создать и подтвердить почту", fontWeight = FontWeight.Bold) }
                TextButton(onClick = vm::toggleRegistration, modifier = Modifier.fillMaxWidth()) { Text("Уже есть кабинет — войти") }
            }
        }
    }
}

@Composable
private fun OtpFields(value: String, onValueChange: (String) -> Unit) {
    val digits = value.filter(Char::isDigit).take(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(6) { index ->
            OutlinedTextField(
                value = digits.getOrNull(index)?.toString().orEmpty(),
                onValueChange = { input ->
                    val next = (digits.take(index) + input.filter(Char::isDigit).takeLast(1) + digits.drop(index + 1)).take(6)
                    onValueChange(next)
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).height(58.dp)
            )
        }
    }
}

@Composable
private fun LoginScreen(state: ArUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ard_logo_screen), "Логотип AR DETECTOR", Modifier.size(92.dp).align(Alignment.CenterHorizontally), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(12.dp))
        Text("AR DETECTOR", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Text("Анализ оборудования", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Промышленное оборудование", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(22.dp))
        Surface(color = SurfaceRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Green))
                    Text("Локальная база доступна", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Вход только для инженеров и администраторов", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = state.email, onValueChange = vm::updateEmail, label = { Text("Рабочая почта") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.password, onValueChange = vm::updatePassword, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (state.loginError != null) Text(state.loginError, color = Red, fontSize = 12.sp)
                Button(onClick = vm::login, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("Войти в кабинет", fontWeight = FontWeight.Bold) }
                TextButton(onClick = vm::openCatalog, modifier = Modifier.fillMaxWidth()) { Text("Продолжить без входа") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Гостевой режим: каталог, анализ и журнал доступны без входа", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CatalogScreen(state: ArUiState, vm: MainViewModel) {
    var query by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Все типы") }
    var showAdd by remember { mutableStateOf(false) }
    val types = listOf("Все типы") + state.equipment.map { it.typeLabel }.distinct()
    val priority = mapOf("pump" to 0, "motor" to 1, "gearbox" to 2, "fan" to 3, "valve" to 4, "pipe" to 5, "tank" to 6, "control_cabinet" to 7, "sensor" to 8, "custom" to 9)
    val filtered = state.equipment.filter { (selectedType == "Все типы" || it.typeLabel == selectedType) && (query.isBlank() || "${it.name} ${it.tag} ${it.location}".contains(query, true)) }.sortedWith(compareBy({ priority[it.type] ?: 10 }, { it.name }))
    val attentionCount = state.equipment.count { it.status == "CHECK_RECOMMENDED" || it.status == "CRITICAL" }
    val serviceSoonCount = state.equipment.count { it.status == "SERVICE_SOON" || it.status == "SERVICE_DUE" }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AR DETECTOR", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("Объекты", fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::startInspection, modifier = Modifier.weight(1f).height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), shape = RoundedCornerShape(10.dp)) {
                    Text("Распознать объект", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
                if (state.user?.role in setOf("admin", "engineer")) {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f).height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), shape = RoundedCornerShape(10.dp)) {
                        Text("Добавить объект", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Green))
            Text("${state.user?.name ?: "Гостевой режим"}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (state.user == null) "БЕЗ ВХОДА" else "АКТИВЕН", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Всего", state.equipment.size.toString(), Primary, Modifier.weight(1f))
            MetricCard("Проверка", attentionCount.toString(), if (attentionCount > 0) Warning else Green, Modifier.weight(1f))
            MetricCard("ТО скоро", serviceSoonCount.toString(), if (serviceSoonCount > 0) Warning else Green, Modifier.weight(1f))
        }
        OutlinedTextField(query, { query = it }, label = { Text("Поиск по названию, тегу, месту") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { type -> FilterChip(selected = selectedType == type, onClick = { selectedType = type }, label = { Text(type, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { item -> EquipmentRow(item) { vm.openEquipmentDetails(item) } }
        }
        BottomNav(state, vm)
    }
    if (showAdd) AddEquipmentDialog({ showAdd = false }, vm)
}

@Composable
private fun BottomNav(state: ArUiState, vm: MainViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = SurfaceDark,
        tonalElevation = 8.dp
    ) {
        Row(Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BottomNavItem("Объекты", NavGlyphKind.OBJECTS, state.screen == AppScreen.CATALOG, vm::openCatalog)
            BottomNavItem("Журнал", NavGlyphKind.HISTORY, state.screen == AppScreen.HISTORY, vm::openHistory)
            BottomNavItem("Профиль", NavGlyphKind.PROFILE, state.screen == AppScreen.PROFILE || state.screen == AppScreen.ADMIN, vm::openProfile)
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(label: String, kind: NavGlyphKind, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Primary else Muted
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).background(if (selected) Primary.copy(alpha = .14f) else Color.Transparent).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { NavGlyph(kind) }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, maxLines = 1, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

private enum class NavGlyphKind { OBJECTS, CAMERA, HISTORY, PROFILE, ADMIN }

@Composable
private fun NavGlyph(kind: NavGlyphKind) {
    val tint = LocalContentColor.current
    // The profile outline has more visual weight at the top than the other
    // glyphs; lower only this icon slightly so it sits optically on the same
    // baseline as its label.
    val verticalOffset = if (kind == NavGlyphKind.PROFILE) 13.dp else 9.dp
    // The hand-drawn glyphs have a little optical whitespace on their left/top
    // edges; this small offset keeps the visible stroke centered over its label.
    Canvas(Modifier.size(24.dp).offset(x = 12.dp, y = verticalOffset)) {
        // Canvas coordinates are physical pixels while the design below uses a
        // 24x24 logical grid. Scale the grid to the actual density so icons
        // stay centered and do not render tiny in the corner on high-density phones.
        val iconScale = size.minDimension / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f)
        scale(iconScale, pivot = center) {
            when (kind) {
            NavGlyphKind.OBJECTS -> {
                drawRoundRect(tint, Offset(3f, 3f), Size(7f, 7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                drawRoundRect(tint, Offset(14f, 3f), Size(7f, 7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                drawRoundRect(tint, Offset(3f, 14f), Size(7f, 7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                drawRoundRect(tint, Offset(14f, 14f), Size(7f, 7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
            }
            NavGlyphKind.CAMERA -> {
                drawRoundRect(tint, topLeft = Offset(3f, 6f), size = Size(18f, 14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f), style = stroke)
                drawCircle(tint, radius = 4f, center = Offset(12f, 13f), style = stroke)
                drawLine(tint, Offset(8f, 6f), Offset(10f, 3f), strokeWidth = 2.2f)
                drawLine(tint, Offset(10f, 3f), Offset(14f, 3f), strokeWidth = 2.2f)
            }
            NavGlyphKind.HISTORY -> {
                drawCircle(tint, radius = 8f, center = Offset(11f, 12f), style = stroke)
                drawLine(tint, Offset(11f, 12f), Offset(11f, 7f), strokeWidth = 2.2f)
                drawLine(tint, Offset(11f, 12f), Offset(15f, 14f), strokeWidth = 2.2f)
            }
            NavGlyphKind.PROFILE -> {
                drawCircle(tint, radius = 3.4f, center = Offset(12f, 8f), style = stroke)
                drawArc(tint, topLeft = Offset(5f, 11f), size = Size(14f, 14f), startAngle = 200f, sweepAngle = 140f, useCenter = false, style = stroke)
            }
            NavGlyphKind.ADMIN -> {
                drawCircle(tint, radius = 4f, center = Offset(12f, 12f), style = stroke)
                drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = stroke)
                drawLine(tint, Offset(12f, 1f), Offset(12f, 4f), strokeWidth = 2.2f)
                drawLine(tint, Offset(12f, 20f), Offset(12f, 23f), strokeWidth = 2.2f)
                drawLine(tint, Offset(1f, 12f), Offset(4f, 12f), strokeWidth = 2.2f)
                drawLine(tint, Offset(20f, 12f), Offset(23f, 12f), strokeWidth = 2.2f)
            }
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: ArUiState, vm: MainViewModel) {
    var filter by remember { mutableStateOf("Все") }
    var deletingInspection by remember { mutableStateOf<InspectionRecord?>(null) }
    val filteredInspections = state.allInspections.filter { filter == "Все" || categoryLabel(it.category) == filter }
    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Журнал проверок", "Результаты осмотров и контроль сроков", vm::openCatalog)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Ближайшие работы", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp); Spacer(Modifier.height(6.dp)) }
            items(state.maintenance.take(6), key = { "m-" + it.equipmentId }) { item ->
                Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(statusColor(item.status)))
                        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.equipmentName, fontWeight = FontWeight.SemiBold); Text(item.dueLabel, color = Muted, fontSize = 12.sp) }
                        Text(statusLabel(item.status), color = statusColor(item.status), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Акты проверок (" + state.allInspections.size + ")", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Все", "НЕДОСТАТОЧНО ДАННЫХ", "РЕКОМЕНДУЕТСЯ ПРОВЕРКА", "ВОЗМОЖНЫЙ ДЕФЕКТ").forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value, fontSize = 10.sp, maxLines = 1) })
                    }
                }
            }
            if (filteredInspections.isEmpty()) item { EmptyState(if (state.allInspections.isEmpty()) "Сохранённых проверок пока нет" else "По выбранному фильтру записей нет") }
            items(filteredInspections, key = { it.id }) { inspection -> InspectionRow(inspection, vm, { vm.openInspectionDetails(inspection.id) }, { deletingInspection = inspection }) }
        }
        BottomNav(state, vm)
    }
    state.inspectionDetails?.let { details -> InspectionDetailsDialog(details) { vm.closeInspectionDetails() } }
    deletingInspection?.let { inspection ->
        AlertDialog(
            onDismissRequest = { deletingInspection = null },
            title = { Text("Удалить акт?") },
            text = { Text("Акт №${inspection.id} будет удалён из журнала вместе с прикреплённым кадром.") },
            confirmButton = { Button(onClick = { vm.deleteInspection(inspection.id); deletingInspection = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deletingInspection = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun InspectionRow(item: InspectionRecord, vm: MainViewModel? = null, onOpen: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() }) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            val preview = remember(item.imagePath) { item.imagePath?.let { path -> runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() } }
            if (preview != null) Image(bitmap = preview.asImageBitmap(), contentDescription = "Сохранённый кадр", modifier = Modifier.size(62.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // Keep the object name and the status on separate lines. A long
                // status label must never reduce the name column to a few pixels.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.equipmentName.ifBlank { item.equipmentId },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "ID акта: ${item.id} • Объект: ${item.equipmentId}",
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        categoryLabel(item.category),
                        color = statusColor(item.category),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(item.summary, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(item.recommendation, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Дата: ${item.createdAt}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onOpen != null || onDelete != null) {
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        onOpen?.let { TextButton(onClick = it) { Text("Открыть акт", color = Primary, fontSize = 11.sp) } }
                        onDelete?.let { TextButton(onClick = it) { Text("Удалить", color = Red, fontSize = 11.sp) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionDetailsDialog(details: InspectionDetails, onDismiss: () -> Unit) {
    val item = details.inspection
    val preview = remember(item.imagePath) { item.imagePath?.let { path -> runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Акт осмотра №${item.id}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Объект: ${item.equipmentName.ifBlank { item.equipmentId }}", fontWeight = FontWeight.SemiBold)
                Text("Номер объекта: ${item.equipmentId}", color = Muted, fontSize = 12.sp)
                Text("Дата: ${item.createdAt}", color = Muted, fontSize = 12.sp)
                Text("Статус: ${categoryLabel(item.category)}", color = statusColor(item.category), fontWeight = FontWeight.Bold)
                item.detectedEquipmentType?.takeIf { it.isNotBlank() }?.let { Text("Распознанный тип: ${it}", color = Color.White, fontSize = 12.sp) }
                item.detectedPumpModel?.takeIf { it.isNotBlank() }?.let { Text("Модель насоса: ${it}", color = Color.White, fontSize = 12.sp) }
                item.detectedAssetId?.takeIf { it.isNotBlank() }?.let { Text("Распознанный объект: ${it}", color = Color.White, fontSize = 12.sp) }
                item.ocrText.takeIf { it.isNotBlank() }?.let { Text("Текст шильдика: ${it.take(180)}", color = Muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                preview?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Кадр осмотра", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit) }
                Text("Результат", color = Primary, fontWeight = FontWeight.Bold)
                Text(item.summary, color = Color.White, fontSize = 13.sp)
                Text("Рекомендация", color = Primary, fontWeight = FontWeight.Bold)
                Text(item.recommendation, color = Muted, fontSize = 13.sp)
                item.confidence?.let { Text("Уверенность модели: ${(it * 100).toInt()}%", color = Muted, fontSize = 12.sp) }
                if (item.status.isNotBlank()) Text("Решение: ${statusLabel(item.status)}", color = statusColor(item.status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (details.components.isNotEmpty()) {
                    Text("Узлы", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    details.components.forEach { component ->
                        Text("• ${component.componentName.ifBlank { component.componentId }}: ${component.status}${component.comment.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}", color = Color.White, fontSize = 12.sp)
                    }
                } else Text("Компонентные результаты в этом акте не записаны.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun ProfileScreen(state: ArUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Профиль", if (state.user == null) "Гостевой режим" else "Локальный кабинет", vm::openCatalog)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(state.user?.name ?: "Гостевой пользователь", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(state.user?.email ?: "Вход не выполнен", color = Muted); Text(state.user?.position ?: "Каталог и анализ доступны без аккаунта", color = Primary, fontWeight = FontWeight.SemiBold) } }
            InfoLine("Состояние", "Данные доступны на устройстве", Green)
            InfoLine("Объектов в каталоге", state.equipment.size.toString(), Color.White)
            InfoLine("Сохранено актов", state.allInspections.size.toString(), Color.White)
            Text("Фотографии, паспорта и результаты сохраняются на этом устройстве.", color = Muted, fontSize = 12.sp)
            if (state.user == null) {
                OutlinedButton(onClick = vm::openLogin, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Войти как инженер или администратор") }
            }
            if (state.user?.role == "engineer") {
                OutlinedButton(onClick = vm::openCatalog, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Открыть каталог оборудования") }
            }
            if (state.user?.role == "admin") {
                OutlinedButton(onClick = vm::openAdmin, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    NavGlyph(NavGlyphKind.ADMIN)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть администрирование")
                }
            }
            if (state.user != null) OutlinedButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) { Text("Выйти в гостевой режим") }
        }
        BottomNav(state, vm)
    }
}

@Composable
private fun AdminScreen(state: ArUiState, vm: MainViewModel) {
    var showEngineerDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Администрирование", "Каталог, пользователи и контроль данных", vm::openCatalog)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AdminMetric("Оборудование", state.equipment.size.toString(), "типов: " + state.equipment.map { it.typeLabel }.distinct().size)
            AdminMetric("Акты проверок", state.allInspections.size.toString(), "сохранены на устройстве")
            Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Права администратора", color = Primary, fontWeight = FontWeight.Bold); Text("Можно добавлять объекты, управлять фотографиями паспортов и просматривать общий журнал.", color = Muted, fontSize = 13.sp); Text("Резервная копия сохраняется в телефоне: Загрузки/KST Energo.", color = Green, fontSize = 12.sp); Text("Выгрузка решений сохраняется в телефоне: Загрузки/KST Energo.", color = Muted, fontSize = 12.sp) } }
            OutlinedButton(onClick = vm::backupDatabase, modifier = Modifier.fillMaxWidth()) { Text("Создать резервную копию базы") }
            OutlinedButton(onClick = vm::exportFeedback, modifier = Modifier.fillMaxWidth()) { Text("Выгрузить решения инженеров") }
            OutlinedButton(onClick = { showEngineerDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Добавить инженера") }
            Text("Пользователи (" + state.users.size + ")", color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            state.users.forEach { user -> Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(user.name, fontWeight = FontWeight.SemiBold); Text(user.email, color = Muted, fontSize = 11.sp); Text(user.position, color = Primary, fontSize = 10.sp) }; TextButton(onClick = { vm.changeRole(user.id, if (user.role == "admin") "engineer" else "admin") }) { Text(if (user.role == "admin") "АДМИН" else "ИНЖЕНЕР", color = if (user.role == "admin") Primary else Green, fontSize = 11.sp) } } } }
            Text("Для демонстрации добавьте объект через кнопку «+ Добавить» в каталоге.", color = Muted, fontSize = 12.sp)
        }
        BottomNav(state, vm)
    }
    if (showEngineerDialog) AddEngineerDialog({ showEngineerDialog = false }, vm)
}

@Composable
private fun AddEngineerDialog(onDismiss: () -> Unit, vm: MainViewModel) {
    var email by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новый инженер") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true); OutlinedTextField(email, { email = it }, label = { Text("Рабочая почта") }, singleLine = true); OutlinedTextField(password, { password = it }, label = { Text("Временный пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) } }, confirmButton = { Button(onClick = { vm.addEngineer(email, name, password); onDismiss() }) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("Назад") }
        Column(Modifier.padding(start = 4.dp)) { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun InfoLine(label: String, value: String, color: Color) { Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted); Text(value, color = color, fontWeight = FontWeight.SemiBold) } }

@Composable
private fun AdminMetric(title: String, value: String, hint: String) { Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(hint, color = Muted, fontSize = 12.sp) }; Text(value, color = Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold) } } }

@Composable
private fun EmptyState(message: String) { Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(message, color = Muted, modifier = Modifier.padding(16.dp)) } }

@Composable
private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddEquipmentDialog(onDismiss: () -> Unit, vm: MainViewModel) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }; var type by remember { mutableStateOf("Насос") }; var typeMenu by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf("") }; var location by remember { mutableStateOf("") }; var specs by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        cover = uri?.let { selectedUri -> context.contentResolver.openInputStream(selectedUri)?.use(android.graphics.BitmapFactory::decodeStream) }
    }
    val canSave = type.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новый объект") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Объект сохранится в локальной базе", color = Muted, fontSize = 12.sp)
            OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, placeholder = { Text("Новый объект — выбранный тип") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(8.dp)) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text("Тип", color = Muted, fontSize = 11.sp); Text(type.ifBlank { "Выберите тип" }, color = Color.White, fontSize = 16.sp) }; Text("⌄") }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    listOf("Насос", "Электродвигатель", "Редуктор", "Вентилятор", "Клапан / задвижка", "Резервуар", "Трубопровод", "Шкаф управления", "Датчик КИПиА").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { type = option; typeMenu = false }) }
                }
            }
            OutlinedTextField(tag, { tag = it }, label = { Text("Инвентарный номер (необязательно)") }, placeholder = { Text("LOCAL-автоматически") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it }, label = { Text("Расположение (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(specs, { specs = it }, label = { Text("Параметры (необязательно)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (cover == null) "Добавить фото объекта" else "Фото объекта выбрано") }
        }
    }, confirmButton = { Button(onClick = { vm.addCustomEquipment(name, type, tag, location, specs, cover); onDismiss() }, enabled = canSave) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun EquipmentDetailScreen(state: ArUiState, vm: MainViewModel) {
    val item = state.selectedEquipment ?: return
    val context = LocalContext.current
    var replacePhotoId by remember(item.id) { mutableStateOf<Long?>(null) }
    var showCameraPhoto by remember(item.id) { mutableStateOf(false) }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)?.let { bitmap -> vm.addEquipmentPhoto(item.id, bitmap, replacePhotoId) }
            }
        }
        replacePhotoId = null
    }
    var showEdit by remember(item.id) { mutableStateOf(false) }
    var showDelete by remember(item.id) { mutableStateOf(false) }
    var showAddComponent by remember(item.id) { mutableStateOf(false) }
    var editingComponent by remember(item.id) { mutableStateOf<ComponentRecord?>(null) }
    var deletingComponent by remember(item.id) { mutableStateOf<ComponentRecord?>(null) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader(item.name, item.typeLabel + " • " + item.tag, vm::openCatalog)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item.photos.isEmpty()) {
                EquipmentCover(item, Modifier.fillMaxWidth().height(150.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { galleryPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Добавить фото", fontSize = 11.sp) }
                    OutlinedButton(onClick = { showCameraPhoto = true }, modifier = Modifier.weight(1f)) { Text("Снять камерой", fontSize = 11.sp) }
                    Text("0/5", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.photos.forEach { photo ->
                            val bitmap = remember(photo.path) { runCatching { android.graphics.BitmapFactory.decodeFile(photo.path) }.getOrNull() }
                            if (bitmap != null) {
                                Box(Modifier.size(112.dp)) {
                                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Фото паспорта", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable { vm.setPrimaryPhoto(photo.id, item.id) }, contentScale = ContentScale.Crop)
                                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .72f))) {
                                        Text(if (photo.isPrimary) "Главное" else "Сделать главным", color = Color.White, fontSize = 9.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setPrimaryPhoto(photo.id, item.id) }.padding(3.dp), textAlign = TextAlign.Center)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                            Text("Заменить", color = Primary, fontSize = 8.sp, modifier = Modifier.clickable { replacePhotoId = photo.id; galleryPicker.launch("image/*") }.padding(3.dp))
                                            Text("Удалить", color = Red, fontSize = 8.sp, modifier = Modifier.clickable { vm.deleteEquipmentPhoto(photo.id, item.id) }.padding(3.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { galleryPicker.launch("image/*") }, enabled = item.photos.size < 5, modifier = Modifier.weight(1f)) { Text("Добавить фото", fontSize = 11.sp) }
                        OutlinedButton(onClick = { showCameraPhoto = true }, enabled = item.photos.size < 5, modifier = Modifier.weight(1f)) { Text("Снять камерой", fontSize = 11.sp) }
                        Text(item.photos.size.toString() + "/5", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
            Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("ТЕКУЩИЙ СТАТУС", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(statusLabel(item.status), color = statusColor(item.status), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Text(item.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(item.location, color = Muted, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { (item.hours / item.toLimit.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, color = statusColor(item.status), trackColor = Outline, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Text("Наработка " + item.hours.toInt() + " / " + item.toLimit.toInt() + " ч", color = Muted, fontSize = 12.sp)
                }
            }
            if (state.user?.role in setOf("admin", "engineer")) {
                OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить объект", color = Red) }
            }
            DetailSection("Паспорт", listOf("Модель" to item.model, "Инвентарный номер" to item.tag, "Серийный номер" to item.serial, "Расположение" to item.location, "Параметры" to item.specs))
            if (state.user?.role in setOf("admin", "engineer")) {
                OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Изменить паспорт и статус")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Компоненты (" + item.componentRecords.size + ")", color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (state.user?.role in setOf("admin", "engineer")) TextButton(onClick = { showAddComponent = true }) { Text("+ Добавить узел", fontSize = 11.sp) }
            }
            item.componentRecords.forEach { component ->
                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Component rows are edited in the persisted checklist.
                        // The general camera already localizes recommended nodes;
                        // do not force a separate camera pass for each node.
                        .clickable {
                            val nextStatus = when (component.status) {
                                "UNKNOWN" -> "NORMAL"
                                "NORMAL" -> "CHECK_RECOMMENDED"
                                "CHECK_RECOMMENDED" -> "POSSIBLE_DEFECT"
                                else -> "UNKNOWN"
                            }
                            vm.updateComponentStatus(component.id, item.id, nextStatus)
                        }
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        val componentColor = when (component.status) { "NORMAL" -> Green; "CHECK_RECOMMENDED" -> Warning; "POSSIBLE_DEFECT", "CRITICAL" -> Red; else -> Muted }
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50.dp)).background(componentColor))
                        Spacer(Modifier.width(10.dp))
                        Text(component.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (component.isCustom && state.user?.role in setOf("admin", "engineer")) {
                            TextButton(onClick = { editingComponent = component }) { Text("Изменить", color = Primary, fontSize = 10.sp) }
                            TextButton(onClick = { deletingComponent = component }) { Text("Удалить", color = Red, fontSize = 10.sp) }
                        } else Text(if (component.status == "UNKNOWN") "Проверить" else "Изменить", color = Primary, fontSize = 11.sp)
                    }
                }
            }
            Text("Последние проверки (" + state.inspections.size + ")", color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (state.inspections.isEmpty()) EmptyState("Проверок для этого объекта ещё нет") else state.inspections.take(3).forEach { InspectionRow(it) }
            Button(onClick = { vm.chooseEquipment(item) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("Проверить этот объект", fontWeight = FontWeight.Bold) }
        }
        BottomNav(state, vm)
    }
    if (showEdit) EditEquipmentDialog(item, { showEdit = false }, vm)
    if (showAddComponent) ComponentNameDialog("Добавить узел", "", { showAddComponent = false }, { vm.addCustomComponent(item.id, it); showAddComponent = false })
    editingComponent?.let { component -> ComponentNameDialog("Изменить узел", component.name, { editingComponent = null }, { vm.renameCustomComponent(component.id, item.id, it); editingComponent = null }) }
    deletingComponent?.let { component ->
        AlertDialog(
            onDismissRequest = { deletingComponent = null },
            title = { Text("Удалить узел?") },
            text = { Text("Узел «${component.name}» будет удалён из этого паспорта.") },
            confirmButton = { Button(onClick = { vm.deleteCustomComponent(component.id, item.id); deletingComponent = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deletingComponent = null }) { Text("Отмена") } }
        )
    }
    if (showCameraPhoto) CameraPhotoDialog(
        onDismiss = { showCameraPhoto = false },
        onCaptured = { bitmap -> vm.addEquipmentPhoto(item.id, bitmap); showCameraPhoto = false }
    )
    if (showDelete) AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("Удалить объект?") }, text = { Text("Карточка, компоненты и локальные акты этого объекта будут удалены.") }, confirmButton = { Button(onClick = { showDelete = false; vm.deleteEquipment(item.id) }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена") } })
}

@Composable
private fun CameraPhotoDialog(onDismiss: () -> Unit, onCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    var cameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraPermission = it }
    LaunchedEffect(Unit) { if (!cameraPermission) permission.launch(Manifest.permission.CAMERA) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceDark, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Фото паспорта", color = Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (cameraPermission) {
                    CameraPreview(capture, Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Text("Разрешите доступ к камере, чтобы добавить фото объекта.", color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить камеру") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(
                        onClick = {
                            captureFrame(context, capture, executor, { bytes, _, _ ->
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(onCaptured)
                            }, {})
                        },
                        enabled = cameraPermission,
                        modifier = Modifier.weight(1f)
                    ) { Text("Снять фото") }
                }
            }
        }
    }
}

@Composable
private fun ComponentNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Название узла") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = name.trim().isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EditEquipmentDialog(item: EquipmentRecord, onDismiss: () -> Unit, vm: MainViewModel) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var tag by remember(item.id) { mutableStateOf(item.tag) }
    var location by remember(item.id) { mutableStateOf(item.location) }
    var specs by remember(item.id) { mutableStateOf(item.specs) }
    var status by remember(item.id) { mutableStateOf(item.status) }
    val statuses = listOf("NORMAL", "CHECK_RECOMMENDED", "SERVICE_SOON", "SERVICE_DUE", "CRITICAL")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить паспорт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(tag, { tag = it }, label = { Text("Инвентарный номер") }, singleLine = true)
                OutlinedTextField(location, { location = it }, label = { Text("Расположение") }, singleLine = true)
                OutlinedTextField(specs, { specs = it }, label = { Text("Параметры") }, minLines = 2)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(statusLabel(status) + " (" + status + ")", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        statuses.forEach { value ->
                            DropdownMenuItem(text = { Text(statusLabel(value)) }, onClick = { status = value; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.updateEquipment(item.id, name, tag, location, specs, status); onDismiss() }, enabled = name.isNotBlank() && tag.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun DetailSection(title: String, values: List<Pair<String, String>>) {
    Text(title, color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) { Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(.38f)); Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(.62f)) } } } }
}

@Composable
private fun EquipmentRow(item: EquipmentRecord, onClick: () -> Unit) {
    val statusColor = statusColor(item.status)
    Surface(color = SurfaceDark, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Outline, RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            EquipmentCover(item, Modifier.size(width = 104.dp, height = 104.dp))
            Box(Modifier.width(4.dp).height(104.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
            Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.typeLabel.uppercase(), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp)); Text(item.name, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${item.tag} • ${item.location}", color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(statusLabel(item.status), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(7.dp)); Text(item.specs, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = (item.hours / item.toLimit.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f), color = statusColor, trackColor = Outline, modifier = Modifier.fillMaxWidth().height(4.dp))
            Spacer(Modifier.height(4.dp)); Text("${item.hours.toInt()} / ${item.toLimit.toInt()} ч • ${item.components.size} узла", color = Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EquipmentCover(item: EquipmentRecord, modifier: Modifier = Modifier) {
    val customBitmap = remember(item.coverPath) {
        item.coverPath?.let { path -> runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    if (customBitmap != null) {
        Image(bitmap = customBitmap.asImageBitmap(), contentDescription = "Фото объекта", modifier = modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        return
    }
    val resource = when (item.id) {
        "AMS-1" -> R.drawable.cover_pump_2
        "AMS-2" -> R.drawable.cover_pump_2
        "AMS-3" -> R.drawable.cover_pump_3
        "AMS-4" -> R.drawable.cover_pump_4
        "WILO-IL" -> R.drawable.cover_pump_wilo
        "MTR-1" -> R.drawable.cover_motor_2
        "VLV-1" -> R.drawable.cover_valve_2
        "PIP-1" -> R.drawable.cover_pipe_2
        else -> when (item.type) {
            "pump" -> R.drawable.cover_pump_2
            "motor" -> R.drawable.cover_motor_2
            "valve" -> R.drawable.cover_valve_2
            "pipe" -> R.drawable.cover_pipe_2
            else -> 0
        }
    }
    if (resource != 0) Image(painterResource(resource), "Фото объекта", modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    else Surface(color = SurfaceRaised, shape = RoundedCornerShape(8.dp), modifier = modifier) { Box(contentAlignment = Alignment.Center) { Text("Фото добавит администратор", color = Muted, fontSize = 10.sp) } }
}

@Composable
private fun ScanScreen(state: ArUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val selected = state.selectedEquipment
    var cameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraPermission = it }
    LaunchedEffect(Unit) { if (!cameraPermission) permission.launch(Manifest.permission.CAMERA) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val scanExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { scanExecutor.shutdown() } }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (cameraPermission) CameraPreview(capture, Modifier.fillMaxSize()) else PermissionView { permission.launch(Manifest.permission.CAMERA) }
        ScanReticle(Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = vm::backToCatalog) { Text("Назад") }
            Text("КАМЕРА • ОСМОТР", color = Green, modifier = Modifier.background(SurfaceDark, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        // Keep guidance compact so it never hides the live camera preview.
        Text(
            "Наведите на объект и снимите кадр",
            color = Color.White.copy(alpha = .9f),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 78.dp, start = 16.dp, end = 16.dp)
                .background(SurfaceDark.copy(alpha = .78f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        if (state.selectedComponent != null) {
            Text("УЗЕЛ: ${state.selectedComponent}", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(top = 130.dp).background(SurfaceDark.copy(alpha = .92f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        // The live preview remains the dominant surface. Long passport and
        // component information is shown after capture on the result screen.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 148.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (selected == null) Surface(color = SurfaceDark.copy(alpha = .96f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Объект будет выбран после распознавания кадра", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            }
            if (selected != null) Surface(color = SurfaceDark.copy(alpha = .92f), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(max = 48.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.typeLabel} • ${selected.tag}", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(selected.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            Button(onClick = { captureFrame(context, capture, scanExecutor, vm::analyzeOffline, vm::cameraCaptureFailed) }, enabled = !state.isLoading && cameraPermission, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(8.dp)) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp) else Text("Сделать фото и анализ", fontWeight = FontWeight.Bold)
            }
            state.statusMessage?.let { Text(it, color = if (it.contains("не найден", ignoreCase = true)) Warning else Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ResultScreen(state: ArUiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::backToCatalog) { Text("Каталог") }
            Text("РЕЗУЛЬТАТ АНАЛИЗА", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 300.dp).padding(horizontal = 12.dp).background(SurfaceDark, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            state.previewBitmap?.let { bitmap ->
                val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Кадр анализа", modifier = Modifier.fillMaxWidth().aspectRatio(aspect))
                ArOverlay(state, Modifier.fillMaxWidth().aspectRatio(aspect))
            }
            Text("КАМЕРА • АНАЛИЗ", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = statusColor(state.visualCategory)
            Text(categoryLabel(state.visualCategory), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(state.selectedEquipment?.name ?: "Объект ещё не выбран", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (state.selectedEquipment == null) {
                Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Выберите объект для паспорта", color = Primary, fontWeight = FontWeight.Bold)
                        Text("Распознавание завершено. Выберите подходящую карточку или создайте новый паспорт.", color = Muted, fontSize = 12.sp)
                        state.recognitionCandidates.forEach { candidate ->
                            OutlinedButton(onClick = { vm.chooseRecognitionCandidate(candidate.equipment) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(candidate.equipment.name, fontWeight = FontWeight.SemiBold)
                                    Text(candidate.equipment.tag + " • " + candidate.equipment.location, color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                        if (state.detectedType != null) {
                            Button(onClick = { vm.createEquipmentFromRecognition(state.detectedType, state.previewBitmap) }, modifier = Modifier.fillMaxWidth()) { Text("Создать новый паспорт") }
                        }
                        OutlinedButton(onClick = vm::backToCatalog, modifier = Modifier.fillMaxWidth()) { Text("Открыть каталог") }
                    }
                }
            }
            Text(state.summary, color = Color.White, fontSize = 15.sp)
            Text(state.recommendation, color = Muted, fontSize = 13.sp)
            Text(state.confidence?.let { "Предварительная уверенность ${(it*100).toInt()}%" } ?: "Оценка недоступна", color = Muted, fontSize = 11.sp)
            if (state.detectedPumpModel != null || state.ocrText.isNotBlank()) {
                Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Распознавание шильдика", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        state.detectedPumpModel?.let { Text("Визуальная модель: " + it, color = Color.White, fontSize = 12.sp) }
                        if (state.ocrText.isNotBlank()) Text("Текст: " + state.ocrText.take(180), color = Muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        state.ocrConfidence?.let { Text("Уверенность OCR: " + (it * 100).toInt() + "%", color = Muted, fontSize = 11.sp) }
                        Text("Номер и карточку подтвердите инженером.", color = Warning, fontSize = 11.sp)
                    }
                }
            }
            Text("Проверьте выделенные узлы и подтвердите решение инженером.", color = Muted, fontSize = 11.sp)
            if (state.affectedComponents.isNotEmpty()) {
                Surface(color = Red.copy(alpha = .10f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Узлы для проверки", color = Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(state.affectedComponents.joinToString(" • "), color = Color.White, fontSize = 12.sp)
                        Text("Рамка на снимке показывает область признака. Состояние узла подтвердите вручную.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            ComponentChecklist(state.selectedEquipment?.componentRecords.orEmpty(), state.selectedEquipment?.id, vm, state.affectedComponents)
            state.recommendedStatus?.let { recommended ->
                Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Рекомендация программы", color = Primary, fontWeight = FontWeight.Bold)
                        Text("Предлагаемый статус: " + statusLabel(recommended), color = statusColor(recommended), fontWeight = FontWeight.SemiBold)
                        state.statusReason?.let { reason -> Text("Основание: " + reason, color = Muted, fontSize = 12.sp) }
                        Text("Примите рекомендацию только после визуальной проверки.", color = Muted, fontSize = 11.sp)
                        OutlinedButton(onClick = vm::applyRecommendedStatus, enabled = !state.inspectionSaved, modifier = Modifier.fillMaxWidth()) { Text(if (state.inspectionSaved) "Статус и акт сохранены" else "Принять статус программы") }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = vm::editPassportAndStatus, enabled = !state.inspectionSaved, modifier = Modifier.weight(1f)) { Text("Изменить статус", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(onClick = vm::keepCurrentStatus, enabled = !state.inspectionSaved, modifier = Modifier.weight(1f)) { Text("Оставить текущий", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            Text("Подтвердите результат анализа:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.confirmVisualStatus("NO_VISIBLE_PROBLEM") }, modifier = Modifier.weight(1f)) { Text("Проблем нет", fontSize = 11.sp) }
                OutlinedButton(onClick = { vm.confirmVisualStatus("CHECK_RECOMMENDED") }, modifier = Modifier.weight(1f)) { Text("Проверить", fontSize = 11.sp) }
                OutlinedButton(onClick = { vm.confirmVisualStatus("POSSIBLE_DEFECT") }, modifier = Modifier.weight(1f)) { Text("Дефект", fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveInspection, enabled = !state.isSaving && !state.inspectionSaved, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (state.inspectionSaved) "Акт сохранён" else "Сохранить акт") }
                OutlinedButton(onClick = vm::backToCatalog, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Завершить") }
            }
            state.statusMessage?.let { Text(it, color = Green, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun CameraPreview(capture: ImageCapture, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        // TextureView compatibility mode keeps the live image visible on devices
        // where SurfaceView screenshots/rendering are black (including some TECNO builds).
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    DisposableEffect(owner, capture) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val activeProvider = future.get()
            provider = activeProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            runCatching { activeProvider.unbindAll(); activeProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun ScanReticle(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width * .72f; val h = size.height * .44f; val left = (size.width - w) / 2; val top = (size.height - h) / 2
        drawRect(Primary.copy(alpha = .75f), Offset(left, top), Size(w, h), style = Stroke(3f))
    }
}

@Composable
private fun ArOverlay(state: ArUiState, modifier: Modifier = Modifier) {
    val textPaint = remember { Paint().apply { color = android.graphics.Color.WHITE; textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true } }
    val bgPaint = remember { Paint().apply { isAntiAlias = true } }
    Canvas(modifier) {
        val sx = size.width / state.sourceImageWidth.coerceAtLeast(1); val sy = size.height / state.sourceImageHeight.coerceAtLeast(1)
        val occupied = mutableListOf<android.graphics.RectF>()
        fun overlaps(rect: android.graphics.RectF): Boolean = occupied.any { android.graphics.RectF.intersects(it, rect) }
        fun placeLabel(text: String, anchorLeft: Float, anchorTop: Float, width: Float, height: Float, color: Color, paint: Paint, above: Boolean): android.graphics.RectF {
            val safeWidth = width.coerceAtMost(size.width - 12f)
            val left = anchorLeft.coerceIn(6f, size.width - safeWidth - 6f)
            val start = if (above) (anchorTop - height - 6f) else (anchorTop + 6f)
            val step = height + 5f
            val candidates = (0..12).map { index ->
                val offset = index * step
                val primary = if (above) start - offset else start + offset
                val alternate = if (above) start + offset else start - offset
                listOf(primary, alternate)
            }.flatten()
            val top = candidates.firstOrNull { candidate ->
                val rect = android.graphics.RectF(left, candidate.coerceIn(6f, size.height - height - 6f), left + safeWidth, candidate.coerceIn(6f, size.height - height - 6f) + height)
                !overlaps(rect)
            } ?: (size.height - height - 6f).coerceAtLeast(6f)
            val rect = android.graphics.RectF(left, top, left + safeWidth, top + height)
            occupied += rect
            bgPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.apply { drawRoundRect(rect, 6f, 6f, bgPaint); drawText(text, rect.left + 8f, rect.top + height - 8f, paint) }
            return rect
        }
        state.boxes.take(5).forEach { box ->
            val color = statusColor(box.status)
            val left = (box.x1*sx).coerceIn(0f, size.width); val top = (box.y1*sy).coerceIn(0f, size.height); val right = (box.x2*sx).coerceIn(left + 4f, size.width); val bottom = (box.y2*sy).coerceIn(top + 4f, size.height); val width = right-left; val height = bottom-top
            drawRect(color.copy(alpha = if (box.status == "POSSIBLE_DEFECT") .22f else .12f), Offset(left,top), Size(width,height)); drawRect(color, Offset(left,top), Size(width,height), style = Stroke(if (box.status == "POSSIBLE_DEFECT") 4f else 2.5f))
            if (box.affectedComponent != null) {
                val cx = left + width / 2f; val cy = top + height / 2f
                drawCircle(color.copy(alpha = .95f), radius = 9f, center = Offset(cx, cy))
                drawCircle(Color.White, radius = 4f, center = Offset(cx, cy))
            }
            val rawLabel = if (box.affectedComponent != null) box.label + " → " + box.affectedComponent else box.label
            // Keep live labels short. The result card carries the full reason
            // and checklist, while the camera overlay only identifies the box.
            val label = rawLabel.take(28).let { if (rawLabel.length > 28) it.dropLast(1) + "…" else it }
            textPaint.textSize = (size.width / 34f).coerceIn(11f, 15f)
            val labelWidth = (textPaint.measureText(label)+16f).coerceAtMost(size.width * .62f)
            placeLabel(label, left, top, labelWidth, 24f, color, textPaint, above = true)
        }
    }
}

@Composable
private fun PassportZoneOverlay(type: String?, objectBox: ArBoundingBox?, sourceWidth: Int, sourceHeight: Int, modifier: Modifier = Modifier) {
    val zones = when (type) {
        "pump" -> listOf("Корпус", "Двигатель", "Уплотнение", "Подшипники")
        "motor" -> listOf("Корпус", "Вентиляция", "Клеммная коробка", "Подшипники")
        "gearbox" -> listOf("Корпус", "Масляная ванна", "Муфта", "Подшипники")
        "fan" -> listOf("Кожух", "Ремень / муфта", "Двигатель", "Подшипники")
        "valve" -> listOf("Корпус", "Привод", "Шток", "Сальник")
        "pipe" -> listOf("Фланец", "Сварной шов", "Изоляция", "Коррозия")
        "tank" -> listOf("Стенка", "Швы", "Арматура", "Утечки")
        else -> emptyList()
    }
    if (zones.isEmpty() || objectBox == null) return
    val zonePaint = remember { Paint().apply { color = android.graphics.Color.WHITE; textSize = 16f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD } }
    val captionPaint = remember { Paint().apply { color = android.graphics.Color.WHITE; textSize = 12f; isAntiAlias = true } }
    Canvas(modifier) {
        val sx = size.width / sourceWidth.coerceAtLeast(1)
        val sy = size.height / sourceHeight.coerceAtLeast(1)
        // Clamp the model box to the canvas before deriving any component guides.
        // This prevents labels from escaping the detected object when the camera
        // sees only a partial object.
        val objectLeft = (objectBox.x1 * sx).coerceIn(0f, size.width)
        val objectTop = (objectBox.y1 * sy).coerceIn(0f, size.height)
        val objectRight = (objectBox.x2 * sx).coerceIn(objectLeft, size.width)
        val objectBottom = (objectBox.y2 * sy).coerceIn(objectTop, size.height)
        val objectWidth = (objectRight - objectLeft).coerceAtLeast(1f)
        val objectHeight = (objectBottom - objectTop).coerceAtLeast(1f)
        val cellW = objectWidth * .42f
        val cellH = objectHeight * .24f
        drawRoundRect(
            color = Primary.copy(alpha = .8f),
            topLeft = Offset(objectLeft, objectTop),
            size = Size(objectWidth, objectHeight),
            style = Stroke(2f)
        )
        drawContext.canvas.nativeCanvas.drawText("Зоны компонентов", objectLeft + 8f, (objectTop - 8f).coerceAtLeast(18f), captionPaint)
        zones.forEachIndexed { index, zone ->
            val col = index % 2; val row = index / 2
            val left = (objectLeft + objectWidth * (.06f + col * .5f)).coerceIn(objectLeft, objectRight - cellW)
            val top = (objectTop + objectHeight * (.06f + row * .46f)).coerceIn(objectTop, objectBottom - cellH)
            drawRoundRect(Warning.copy(alpha = .10f), topLeft = Offset(left, top), size = Size(cellW, cellH))
            drawRoundRect(Warning.copy(alpha = .95f), topLeft = Offset(left, top), size = Size(cellW, cellH), style = Stroke(2.5f))
            drawContext.canvas.nativeCanvas.drawText(zone, left + 8f, (top + 24f).coerceAtMost(objectBottom - 8f), zonePaint)
        }
    }
}

@Composable
private fun ComponentChecklist(components: List<ComponentRecord>, equipmentId: String?, vm: MainViewModel, affectedComponents: List<String> = emptyList()) {
    if (components.isEmpty()) return
    val checked = components.count { it.status != "UNKNOWN" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Чек-лист узлов • $checked из ${components.size} проверено", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Проверьте каждый узел и выберите его состояние. Нажмите на строку, чтобы изменить статус.", color = Muted, fontSize = 11.sp)
        components.forEach { component ->
            val status = component.status
            val label = when (status) { "NORMAL" -> "Норма"; "CHECK_RECOMMENDED" -> "Внимание"; "POSSIBLE_DEFECT", "CRITICAL" -> "Дефект"; else -> "Не проверено" }
            val color = when (status) { "NORMAL" -> Green; "CHECK_RECOMMENDED" -> Warning; "POSSIBLE_DEFECT", "CRITICAL" -> Red; else -> Muted }
            val affected = component.name in affectedComponents
            Surface(color = if (affected) Red.copy(alpha = .12f) else SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().border(if (affected) 1.dp else 0.dp, if (affected) Red else Color.Transparent, RoundedCornerShape(8.dp)).clickable {
                if (equipmentId != null) {
                    val next = when (status) { "UNKNOWN" -> "NORMAL"; "NORMAL" -> "CHECK_RECOMMENDED"; "CHECK_RECOMMENDED" -> "POSSIBLE_DEFECT"; else -> "UNKNOWN" }
                    vm.updateComponentStatus(component.id, equipmentId, next)
                }
            }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(if (affected) Red else color))
                    Spacer(Modifier.width(10.dp)); Text(component.name, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = if (affected) FontWeight.Bold else FontWeight.Normal)
                    if (affected) Text("ПРИЗНАК", color = Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PermissionView(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Нужен доступ к камере", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp)); Text("Камера используется для локального предварительного анализа.", color = Muted)
        Spacer(Modifier.height(16.dp)); Button(onClick = onRequest) { Text("Разрешить камеру") }
    }
}

private fun statusColor(status: String): Color = when (status) { "POSSIBLE_DEFECT", "CRITICAL" -> Red; "CHECK_RECOMMENDED", "SERVICE_SOON", "SERVICE_DUE" -> Warning; "NO_VISIBLE_PROBLEM", "NORMAL" -> Green; else -> Color.LightGray }
private fun statusLabel(status: String): String = when(status) { "NORMAL" -> "НОРМА"; "SERVICE_SOON" -> "СКОРО ТО"; "CHECK_RECOMMENDED" -> "ПРОВЕРИТЬ"; "CRITICAL" -> "КРИТИЧНО"; else -> "НЕТ ДАННЫХ" }
private fun categoryLabel(category: String): String = when(category) { "POSSIBLE_DEFECT" -> "ВОЗМОЖНЫЙ ДЕФЕКТ"; "CHECK_RECOMMENDED" -> "РЕКОМЕНДУЕТСЯ ПРОВЕРКА"; "NO_VISIBLE_PROBLEM" -> "ВИДИМЫХ ПРОБЛЕМ НЕ ОБНАРУЖЕНО"; else -> "НЕДОСТАТОЧНО ДАННЫХ" }

private fun captureFrame(context: Context, capture: ImageCapture, executor: java.util.concurrent.Executor, onCaptured: (ByteArray, Int, Int) -> Unit, onError: () -> Unit) {
    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val bitmap = image.toBitmap(); val degrees = image.imageInfo.rotationDegrees
                val rotated = if (degrees != 0) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true) else bitmap
                val stream = ByteArrayOutputStream(); rotated.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                val bytes = stream.toByteArray(); val width = rotated.width; val height = rotated.height; image.close()
                ContextCompat.getMainExecutor(context).execute { onCaptured(bytes, width, height) }
            } catch (_: Exception) { image.close(); onError() }
        }
        override fun onError(exception: ImageCaptureException) = onError()
    })
}

