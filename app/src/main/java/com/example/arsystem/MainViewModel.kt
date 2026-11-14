package com.example.arsystem

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Crop to the same central inspection window shown by the camera reticle. This keeps monitor edges and keyboard out of CV input. */
private fun cropInspectionRegion(source: Bitmap): Bitmap {
    val cropWidth = (source.width * .76f).toInt().coerceIn(64, source.width)
    val cropHeight = (source.height * .58f).toInt().coerceIn(64, source.height)
    val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
    val top = ((source.height - cropHeight) / 2).coerceAtLeast(0)
    return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
}

enum class AppScreen { LOGIN, REGISTER, VERIFY, CATALOG, DETAIL, SCAN, RESULT, HISTORY, PROFILE, ADMIN }

data class ArBoundingBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String, val status: String, val confidence: Float)

data class ArUiState(
    val screen: AppScreen = AppScreen.CATALOG,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val user: LocalUser? = null,
    val equipment: List<EquipmentRecord> = emptyList(),
    val selectedEquipment: EquipmentRecord? = null,
    val selectedComponent: String? = null,
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val position: String = "Инженер по эксплуатации",
    val registration: Boolean = false,
    val verificationCode: String = "",
    val pendingEmail: String = "",
    val pendingName: String = "",
    val pendingPosition: String = "Инженер по эксплуатации",
    val localVerificationCode: String? = null,
    val loginError: String? = null,
    val statusMessage: String? = null,
    val visualCategory: String = "INSUFFICIENT_DATA",
    val predictedCategory: String? = null,
    val summary: String = "Наведите камеру на объект и выполните анализ",
    val recommendation: String = "Выберите объект из каталога и сделайте снимок",
    val boxes: List<ArBoundingBox> = emptyList(),
    val sourceImageWidth: Int = 1000,
    val sourceImageHeight: Int = 1000,
    val confidence: Float? = null,
    val inspections: List<InspectionRecord> = emptyList(),
    val allInspections: List<InspectionRecord> = emptyList(),
    val maintenance: List<MaintenanceRecord> = emptyList(),
    val users: List<LocalUser> = emptyList(),
    val feedbackCount: Int = 0,
    val modelAvailable: Boolean = false,
    val previewBitmap: Bitmap? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = OfflineStore(application)
    private val detector = OnnxDetector(application)
    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()
    private val sessionPrefs = application.getSharedPreferences("kst_session", Context.MODE_PRIVATE)

    init {
        // The guest-first release deliberately clears sessions created by the
        // previous registration flow once, while preserving equipment/history.
        if (!sessionPrefs.getBoolean("guest_mode_migrated", false)) {
            sessionPrefs.edit().clear().putBoolean("guest_mode_migrated", true).apply()
        }
        val sessionEmail = sessionPrefs.getString("session_email", "") ?: ""
        val restoredUser = store.findUser(sessionEmail)
        if (sessionEmail.isNotBlank() && restoredUser == null) sessionPrefs.edit().remove("session_email").remove("session_user_id").apply()
        _uiState.update {
            it.copy(
                screen = AppScreen.CATALOG,
                user = restoredUser,
                email = "",
                equipment = store.equipment(),
                allInspections = store.allInspections(),
                maintenance = store.maintenance(),
                users = store.users(),
                feedbackCount = store.feedbackCount(),
                modelAvailable = detector.isAvailable()
            )
        }
    }

    private fun persistSession(user: LocalUser) {
        // Only the local identity is persisted. Passwords remain PBKDF2 hashes in SQLite
        // and are never copied to preferences.
        sessionPrefs.edit()
            .putString("last_email", user.email)
            .putString("session_email", user.email)
            .putLong("session_user_id", user.id)
            .putBoolean("session_disabled", false)
            .apply()
    }
    fun updateEmail(value: String) = _uiState.update { it.copy(email = value, loginError = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, loginError = null) }
    fun updateName(value: String) = _uiState.update { it.copy(name = value, loginError = null) }
    fun updatePosition(value: String) = _uiState.update { it.copy(position = value, loginError = null) }
    fun updateVerificationCode(value: String) = _uiState.update { it.copy(verificationCode = value, loginError = null) }
    fun toggleRegistration() = _uiState.update {
        if (it.registration) it.copy(registration = false, screen = AppScreen.LOGIN, loginError = null)
        else it.copy(registration = true, screen = AppScreen.REGISTER, loginError = null)
    }
    fun openLogin() = _uiState.update { it.copy(screen = AppScreen.LOGIN, loginError = null, password = "") }
    fun login() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val email = state.email.trim()
            val remote = runCatching { ApiClient.login(email, state.password) }.getOrNull()
            val user = if (remote?.accepted == true) {
                store.cacheAuthenticatedUser(email, state.password, remote.name, remote.position, remote.role) ?: store.login(email, state.password)
            } else store.login(email, state.password)
            if (user == null) _uiState.update { it.copy(loginError = "Неверная почта или пароль") }
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, loginError = null, password = "", statusMessage = if (remote?.accepted == true) "Вход подтверждён, данные доступны офлайн" else "Локальный вход выполнен") } }
        }
    }
    fun register() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (state.name.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(state.email.trim()).matches() || state.password.length < 8) {
                _uiState.update { it.copy(loginError = "Укажите имя, корректную почту и пароль от 8 символов") }; return@launch
            }
            val email = state.email.trim(); val name = state.name.trim()
            try {
                val online = ApiClient.register(email, state.password, name, state.position)
                if (!online.accepted) {
                    _uiState.update { it.copy(loginError = online.message) }
                    return@launch
                }
                if (online.verificationRequired) {
                    _uiState.update { it.copy(screen = AppScreen.VERIFY, pendingEmail = email, pendingName = name, pendingPosition = state.position, localVerificationCode = online.localCode, loginError = null, statusMessage = online.message) }
                    return@launch
                }
            } catch (_: Exception) {
                val localCode = "102102"
                _uiState.update { it.copy(screen = AppScreen.VERIFY, pendingEmail = email, pendingName = name, pendingPosition = state.position, localVerificationCode = localCode, loginError = null, statusMessage = "Сервер недоступен. Локальный demo-код: $localCode") }
                return@launch
            }
            val user = store.register(email, state.password, name, state.position)
            if (user == null) _uiState.update { it.copy(loginError = "Пользователь с такой почтой уже существует") }
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, loginError = null, statusMessage = "Личный кабинет создан офлайн") } }
        }
    }
    fun verifyRegistration() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (state.pendingEmail.isBlank() || state.verificationCode.length != 6) { _uiState.update { it.copy(loginError = "Введите шестизначный код из письма") }; return@launch }
            val remoteVerified = runCatching { ApiClient.verify(state.pendingEmail, state.verificationCode) }.getOrDefault(false)
            val verified = remoteVerified || (state.localVerificationCode != null && state.verificationCode == state.localVerificationCode)
            if (!verified) { _uiState.update { it.copy(loginError = "Код неверный или срок его действия истёк") }; return@launch }
            val user = store.register(state.pendingEmail, state.password, state.pendingName, state.pendingPosition)
            if (user == null) _uiState.update { it.copy(loginError = "Пользователь уже сохранён на устройстве") }
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, localVerificationCode = null, loginError = null, statusMessage = "Почта подтверждена, кабинет сохранён офлайн") } }
        }
    }
    fun completeOfflineRegistration() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val user = store.register(state.pendingEmail.ifBlank { state.email.trim() }, state.password, state.pendingName.ifBlank { state.name.trim() }, state.pendingPosition)
            if (user == null) _uiState.update { it.copy(loginError = "Пользователь уже сохранён на устройстве") }
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, loginError = null, statusMessage = "Офлайн-кабинет создан без подтверждения сети") } }
        }
    }
    fun logout() {
        sessionPrefs.edit().remove("session_email").remove("session_user_id").apply()
        _uiState.update {
        ArUiState(
            equipment = store.equipment(),
            allInspections = store.allInspections(),
            maintenance = store.maintenance(),
            users = store.users(),
            modelAvailable = detector.isAvailable()
        )
        }
    }
    fun openCatalog() = _uiState.update { it.copy(screen = AppScreen.CATALOG, equipment = store.equipment()) }
    fun openEquipmentDetails(item: EquipmentRecord) = _uiState.update { it.copy(screen = AppScreen.DETAIL, selectedEquipment = item, inspections = store.inspections(item.id)) }
    fun startInspection() = _uiState.update { it.copy(screen = AppScreen.SCAN, selectedComponent = null) }
    fun startComponentInspection(component: String) = _uiState.update {
        it.copy(
            screen = AppScreen.SCAN,
            selectedComponent = component,
            boxes = emptyList(),
            visualCategory = "INSUFFICIENT_DATA",
            summary = "Готов к проверке узла «$component»",
            recommendation = "Наведите камеру на выбранный узел и сделайте снимок",
            statusMessage = "Выбран узел: $component"
        )
    }
    fun openHistory() = _uiState.update { it.copy(screen = AppScreen.HISTORY, allInspections = store.allInspections(), maintenance = store.maintenance()) }
    fun openProfile() = _uiState.update { it.copy(screen = AppScreen.PROFILE) }
    fun openAdmin() = _uiState.update { if (it.user?.role == "admin") it.copy(screen = AppScreen.ADMIN) else it }
    fun addEngineer(email: String, name: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.user?.role != "admin") { _events.emit("Требуются права администратора"); return@launch }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() || name.isBlank() || password.length < 8) {
                _events.emit("Нужны имя, корректная почта и пароль от 8 символов"); return@launch
            }
            val user = store.register(email.trim(), password, name.trim())
            if (user == null) _events.emit("Пользователь с такой почтой уже существует")
            else { _uiState.update { it.copy(users = store.users()) }; _events.emit("Инженер добавлен в локальную базу") }
        }
    }
    fun changeRole(userId: Long, role: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.user?.role != "admin") { _events.emit("Требуются права администратора"); return@launch }
            if (_uiState.value.user?.id == userId && role != "admin") {
                _events.emit("Нельзя снять роль у текущего администратора")
                return@launch
            }
            if (store.updateRole(userId, role)) { _uiState.update { it.copy(users = store.users()) }; _events.emit("Роль пользователя обновлена") }
        }
    }
    fun backupDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.user?.role != "admin") { _events.emit("Требуются права администратора"); return@launch }
            val path = store.backupDatabase()
            _events.emit(if (path == null) "Не удалось создать резервную копию" else "Резервная копия создана локально")
        }
    }
    fun exportFeedback() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.user?.role != "admin") { _events.emit("Требуются права администратора"); return@launch }
            val path = store.exportFeedback()
            _events.emit(if (path == null) "Очередь обратной связи пуста или не выгружена" else "Обратная связь выгружена локально")
        }
    }
    fun chooseEquipment(item: EquipmentRecord) = _uiState.update { it.copy(selectedEquipment = item, selectedComponent = null, screen = AppScreen.SCAN, boxes = emptyList(), inspections = store.inspections(item.id), visualCategory = "INSUFFICIENT_DATA", summary = "Готов к предварительному анализу: ${item.name}", recommendation = "Сделайте фото объекта в центре кадра и запустите анализ") }
    fun cameraCaptureFailed() { viewModelScope.launch { _events.emit("Не удалось получить кадр. Проверьте разрешение и повторите съёмку.") } }
    fun backToCatalog() = _uiState.update { it.copy(screen = AppScreen.CATALOG, selectedEquipment = null, boxes = emptyList()) }
    fun addCustomEquipment(name: String, type: String, tag: String, location: String, specs: String, cover: Bitmap? = null) {
        if (name.isBlank() || tag.isBlank()) return
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для изменения каталога") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.addCustomEquipment(name.trim(), type.trim().ifBlank { "Пользовательский объект" }, tag.trim(), location.trim().ifBlank { "Не указано" }, specs.trim().ifBlank { "Параметры не заполнены" }, cover)
            _uiState.update { it.copy(equipment = store.equipment(), statusMessage = "Объект добавлен в локальный каталог") }
            _events.emit("Пользовательский объект сохранён")
        }
    }
    fun updateEquipment(id: String, name: String, tag: String, location: String, specs: String, status: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для изменения каталога") }
            return
        }
        if (name.isBlank() || tag.isBlank()) {
            viewModelScope.launch { _events.emit("Название и инвентарный номер обязательны") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (store.updateEquipment(id, name, tag, location, specs, status)) {
                val refreshed = store.equipment()
                _uiState.update { current ->
                    current.copy(
                        equipment = refreshed,
                        selectedEquipment = refreshed.firstOrNull { it.id == id } ?: current.selectedEquipment,
                        statusMessage = "Паспорт объекта обновлён"
                    )
                }
                _events.emit("Паспорт объекта обновлён локально")
            } else {
                _events.emit("Не удалось обновить объект")
            }
        }
    }
    fun deleteEquipment(id: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для удаления объекта") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (store.deleteEquipment(id)) {
                _uiState.update { it.copy(equipment = store.equipment(), selectedEquipment = null, screen = AppScreen.CATALOG, statusMessage = "Объект удалён из каталога") }
                _events.emit("Объект удалён")
            } else _events.emit("Не удалось удалить объект")
        }
    }
    fun analyzeOffline(imageBytes: ByteArray, width: Int, height: Int) {
        val selected = _uiState.value.selectedEquipment ?: return
        if (_uiState.value.isLoading) return
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Локальный анализ изображения…") }
            val sourceBitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull()
            val bitmap = sourceBitmap?.let(::cropInspectionRegion)
            val analysisWidth = bitmap?.width ?: width.coerceAtLeast(1)
            val analysisHeight = bitmap?.height ?: height.coerceAtLeast(1)
            val usableFrame = bitmap?.let(detector::isUsableFrame) == true
            val inference = if (usableFrame && detector.isAvailable()) runCatching { detector.detect(bitmap!!) } else Result.success(emptyList())
            val detections = inference.getOrDefault(emptyList())
            val defectLabels = setOf("Возможная утечка", "Возможная коррозия", "След перегрева")
            val defect = detections.firstOrNull { it.label in defectLabels && it.confidence >= 0.65f }
            val frameArea = analysisWidth.toFloat() * analysisHeight.toFloat()
            fun areaRatio(detection: ModelDetection): Float = ((detection.x2 - detection.x1).coerceAtLeast(0f) * (detection.y2 - detection.y1).coerceAtLeast(0f)) / frameArea
            // Defect labels in the supplied case set are broad visual regions.
            // Keep them visible up to 94% of the frame so a corrosion/leak mark
            // is still shown instead of being silently discarded as oversized.
            val localizedDefect = detections.firstOrNull { it.label in defectLabels && it.confidence >= 0.65f && areaRatio(it) in .02f.. .86f }
            val equipmentDetections = detections.filter { it.label !in defectLabels && it.label != "Зона осмотра" }
            val supportedTypes = mapOf(
                "pump" to "Насос",
                "motor" to "Электродвигатель",
                "gearbox" to "Редуктор",
                "fan" to "Вентилятор",
                "valve" to "Клапан / задвижка",
                "tank" to "Резервуар",
                "pipe" to "Трубопровод",
                "control_cabinet" to "Шкаф управления",
                "sensor" to "Датчик КИПиА",
                "custom" to "Пользовательский объект"
            )
            val selectedIsSupported = selected.type in supportedTypes.keys
            val specializedModelTypes = setOf("pump", "motor", "valve", "pipe")
            val hasSpecializedModel = selected.type in specializedModelTypes
            val expectedLabel = supportedTypes[selected.type]
            val rawMatchingEquipmentDetection = equipmentDetections.firstOrNull { detection ->
                expectedLabel?.let { expected -> detection.label == expected || (selected.type == "valve" && detection.label == "Клапан / задвижка") } == true
            }
            val matchingEquipmentDetection = rawMatchingEquipmentDetection?.takeIf { detection ->
                val area = (detection.x2 - detection.x1).coerceAtLeast(0f) * (detection.y2 - detection.y1).coerceAtLeast(0f)
                val frameArea = analysisWidth.coerceAtLeast(1).toFloat() * analysisHeight.coerceAtLeast(1).toFloat()
                val boxWidth = (detection.x2 - detection.x1).coerceAtLeast(0f)
                val boxHeight = (detection.y2 - detection.y1).coerceAtLeast(0f)
                val aspect = boxWidth / boxHeight.coerceAtLeast(1f)
                // The original training labels contain a single almost-full-frame
                // box. It is useful for classification but cannot anchor AR nodes.
                // Compare with the cropped analysis frame (not the camera source) and
                // reject implausible slivers before drawing anything.
                detection.confidence >= 0.50f &&
                    area / frameArea in 0.04f..0.78f &&
                    aspect in 0.18f..5.5f
            }
            // Prefer the selected catalog class when several objects are visible in one frame.
            // A secondary class must not turn a correctly framed pump into a false mismatch.
            val equipmentDetection = matchingEquipmentDetection
            // A broad class prediction is useful for guidance but must not be
            // presented as a localized AR box. Only a compact matching box is
            // treated as a confirmed object location.
            val selectedTypeMatches = matchingEquipmentDetection != null
            // Show every compact equipment detection in the captured frame. This lets
            // the engineer see where each object is located when several objects are
            // visible; oversized training boxes are still suppressed. Defect signs
            // remain provisional and are shown only when they have a usable region.
            val visibleDetections = detections.filter { detection ->
                val ratio = areaRatio(detection)
                val compact = ratio in 0.04f..0.78f && detection.confidence >= 0.45f
                val visibleDefect = detection.label in defectLabels && ratio in 0.02f..0.86f && detection.confidence >= 0.65f
                visibleDefect ||
                    (detection.label !in defectLabels && detection.label != "Зона осмотра" && compact)
            }
            val category = when {
                bitmap == null || !usableFrame -> "INSUFFICIENT_DATA"
                localizedDefect != null -> "POSSIBLE_DEFECT"
                defect != null -> "POSSIBLE_DEFECT"
                !selectedIsSupported -> "INSUFFICIENT_DATA"
                !hasSpecializedModel -> "CHECK_RECOMMENDED"
                selectedTypeMatches -> "CHECK_RECOMMENDED"
                else -> "INSUFFICIENT_DATA"
            }
            val confidence: Float? = localizedDefect?.confidence ?: rawMatchingEquipmentDetection?.confidence ?: equipmentDetection?.confidence
            val detectedObjects = visibleDetections
                .filter { it.label !in defectLabels }
                .distinctBy { it.label }
                .joinToString { it.label }
            val defectExplanation = when (localizedDefect?.label) {
                "Возможная утечка" -> "Проверьте уплотнение, фланцы и места соединений: возможен выход рабочей среды."
                "Возможная коррозия" -> "Проверьте корпус, фланец и сварные швы: видны признаки коррозионного повреждения."
                "След перегрева" -> "Проверьте вентиляцию, нагрузку и клеммные соединения: виден след перегрева."
                else -> null
            }
            val defectRecommendation = when (localizedDefect?.label) {
                "Возможная утечка" -> "Остановите оборудование по регламенту и проверьте уплотнение и соединения."
                "Возможная коррозия" -> "Очистите зону, оцените толщину/состояние металла и занесите замечание в ремонт."
                "След перегрева" -> "Проверьте охлаждение, токовую нагрузку и клеммную коробку до продолжения работы."
                else -> null
            }
            val summary = if (bitmap == null) {
                "Не удалось прочитать кадр. Автоматическая оценка не выполнена."
            } else if (!usableFrame) {
                "Кадр однотонный, слишком тёмный или слишком яркий. Оценка не выполнена."
            } else if (inference.isFailure) {
                "Локальная модель завершилась с ошибкой. Автоматическая оценка не выполнена."
            } else if (!detector.isAvailable()) {
                "Обученная локальная модель пока не загружена. Выполните ручную проверку объекта."
            } else if (defect != null && localizedDefect == null) {
                "Модель видит возможный признак «" + defect.label + "», но точная область не локализована."
            } else if (localizedDefect != null) {
                "Модель обнаружила: " + localizedDefect.label + ". " + (defectExplanation ?: "Проверьте выделенную область вручную.")
            } else if (defect != null) {
                "Модель обнаружила признак возможного дефекта: " + defect.label + "."
            } else if (detectedObjects.isNotBlank() && !selectedTypeMatches) {
                "В кадре найдены объекты: " + detectedObjects + ". Выберите нужный объект в каталоге."
            } else if (!selectedIsSupported) {
                "Тип оборудования не найден в каталоге."
            } else if (!hasSpecializedModel) {
                "Для «" + selected.typeLabel + "» включён универсальный режим: автоматическая фиксация кадра и ручной чек-лист."
            } else if (rawMatchingEquipmentDetection != null && matchingEquipmentDetection == null) {
                "Класс объекта распознан, но точная рамка не подтверждена датасетом. Проверьте объект вручную."
            } else if (equipmentDetection == null) {
                "Модель не смогла уверенно идентифицировать объект в кадре. Визуальный вывод не сформирован."
            } else if (!selectedTypeMatches) {
                "В кадре обнаружен другой тип объекта. Проверьте выбор оборудования и повторите анализ."
            } else {
                "Тип объекта распознан. Данных модели пока недостаточно, чтобы подтвердить отсутствие дефектов."
            }
            val recommendation = when {
                !usableFrame -> "Сделайте новый кадр с объектом в фокусе и достаточным освещением."
                inference.isFailure -> "Повторите анализ. Если ошибка повторяется, зафиксируйте результат вручную."
                !detector.isAvailable() -> "Подтвердите результат вручную или добавьте локальную модель equipment_detector.onnx."
                defect != null && localizedDefect == null -> "Проверьте объект целиком вручную: текущий датасет не содержит точной рамки этого дефекта."
                localizedDefect != null -> defectRecommendation ?: "Проверьте выделенную область и подтвердите результат инженером."
                defect != null -> "Проверьте выделенную область и подтвердите результат инженером."
                !selectedIsSupported -> "Выберите объект из каталога или добавьте его через кабинет администратора."
                !hasSpecializedModel -> "Проверьте компоненты по чек-листу и сохраните результат инженера."
                equipmentDetection == null -> "Повторите кадрирование, улучшите освещение или подтвердите объект вручную."
                !selectedTypeMatches -> "Проверьте выбранный объект, затем повторите кадрирование."
                else -> "Выполните визуальную проверку, подтвердите статус инженером и сохраните акт."
            }
            val boxes = visibleDetections.map { detection -> ArBoundingBox(detection.x1, detection.y1, detection.x2, detection.y2, detection.label, if (detection.label in defectLabels) "POSSIBLE_DEFECT" else "CHECK_RECOMMENDED", detection.confidence) }.toMutableList()
            val resultWidth = analysisWidth
            val resultHeight = analysisHeight
            _uiState.update { it.copy(screen = AppScreen.RESULT, isLoading = false, statusMessage = if (matchingEquipmentDetection != null) "Точная рамка объекта получена; узлы подтверждаются по чек-листу" else if (rawMatchingEquipmentDetection != null) "Класс объекта найден, но точная рамка не подтверждена" else if (detector.isAvailable()) "Модель не подтвердила выбранный тип объекта" else "Недостаточно данных для автоматической оценки", visualCategory = category, predictedCategory = category, confidence = confidence, summary = summary, recommendation = recommendation, boxes = boxes, previewBitmap = bitmap, sourceImageWidth = resultWidth, sourceImageHeight = resultHeight) }
        }
    }
    fun confirmVisualStatus(category: String) {
        val actor = if (_uiState.value.user == null) "Пользователь" else "Инженер"
        val summary = when (category) { "NO_VISIBLE_PROBLEM" -> "$actor подтвердил: видимых проблем не обнаружено"; "POSSIBLE_DEFECT" -> "$actor зафиксировал признаки возможного дефекта"; else -> "$actor отметил необходимость дополнительной проверки" }
        val recommendation = when (category) { "NO_VISIBLE_PROBLEM" -> "Продолжить эксплуатацию и повторить проверку по регламенту"; "POSSIBLE_DEFECT" -> "Проверить компонент и выполнить необходимые работы"; else -> "Запланировать дополнительную проверку компонента" }
        _uiState.update { it.copy(visualCategory = category, summary = summary, recommendation = recommendation) }
    }
    fun saveInspection() {
        val state = _uiState.value; val item = state.selectedEquipment ?: return; val user = state.user
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val inspectionId = runCatching { store.saveInspection(item.id, user?.id, state.visualCategory, state.confidence, state.summary, state.recommendation, state.previewBitmap) }.getOrNull()
            val saved = inspectionId != null
            if (inspectionId != null) {
                // Keep the model's output and the engineer's final confirmation
                // together for a later, controlled retraining export.
                runCatching {
                    store.saveFeedback(
                        inspectionId = inspectionId,
                        equipmentId = item.id,
                        userId = user?.id,
                        predictedCategory = state.predictedCategory ?: state.visualCategory,
                        correctedCategory = state.visualCategory,
                        component = state.selectedComponent,
                        comment = if (user == null) "Подтверждено пользователем в гостевом режиме" else "Подтверждено инженером в акте осмотра"
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isSaving = false,
                    statusMessage = if (saved) "Сохранено в локальную историю" else "Не удалось сохранить акт",
                    inspections = if (saved) store.inspections(item.id) else it.inspections,
                    allInspections = if (saved) store.allInspections() else it.allInspections
                    ,feedbackCount = store.feedbackCount()
                )
            }
            _events.emit(if (saved) "Акт осмотра сохранён на устройстве" else "Ошибка локальной базы. Акт не сохранён.")
        }
    }
}
