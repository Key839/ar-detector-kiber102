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

/** Maps a detected visual sign to the equipment nodes that must be checked.
 * The mapping is guidance for the engineer; it never claims that a node is
 * defective without the model box and a human confirmation.
 */
private fun defectComponents(label: String, type: String?): List<String> = when (label) {
    "Возможная утечка" -> when (type) {
        "pump" -> listOf("Торцевое уплотнение", "Фланцы", "Утечки")
        "gearbox" -> listOf("Сальники", "Масляная ванна", "Утечки")
        "fan" -> listOf("Кожух", "Ремень/муфта", "Утечки")
        "valve" -> listOf("Сальник", "Фланцы", "Утечка")
        "pipe" -> listOf("Фланцы", "Сварные швы", "Утечка")
        "tank" -> listOf("Сварные швы", "Патрубки", "Утечки")
        else -> listOf("Утечки", "Соединения")
    }
    "Возможная коррозия" -> when (type) {
        "pump" -> listOf("Корпус", "Фланцы", "Ржавчина/коррозия")
        "motor" -> listOf("Корпус", "Крепление", "Ржавчина/коррозия")
        "gearbox" -> listOf("Корпус", "Сальники", "Ржавчина/коррозия")
        "fan" -> listOf("Кожух", "Крепление", "Ржавчина/коррозия")
        "valve" -> listOf("Корпус", "Фланцы", "Ржавчина/коррозия")
        "pipe" -> listOf("Труба", "Сварные швы", "Ржавчина/коррозия")
        "tank" -> listOf("Стенка корпуса", "Сварные швы", "Ржавчина/коррозия")
        "control_cabinet" -> listOf("Корпус шкафа", "Кабельные вводы", "Ржавчина/коррозия")
        "sensor" -> listOf("Корпус датчика", "Крепление", "Ржавчина/коррозия")
        else -> listOf("Корпус", "Ржавчина/коррозия")
    }
    "След перегрева" -> when (type) {
        "motor" -> listOf("Вентиляция", "Клеммная коробка", "След перегрева")
        "pump" -> listOf("Электродвигатель", "Кабельная коробка", "След перегрева")
        "gearbox" -> listOf("Масляная ванна", "Подшипники", "След перегрева")
        "fan" -> listOf("Двигатель", "Вентиляция", "След перегрева")
        "control_cabinet" -> listOf("Клеммная коробка", "Автоматы и защита", "След перегрева")
        else -> listOf("Крепление", "След перегрева")
    }
    else -> emptyList()
}

enum class AppScreen { LOGIN, REGISTER, VERIFY, CATALOG, DETAIL, SCAN, RESULT, HISTORY, PROFILE, ADMIN }

data class ArBoundingBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String, val status: String, val confidence: Float, val affectedComponent: String? = null)
data class RecognitionCandidate(
    val equipment: EquipmentRecord,
    val confidence: Float,
    val detectedType: String,
    val model: String? = null,
    val matchReason: String = "Тип оборудования"
)

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
    val recognitionCandidates: List<RecognitionCandidate> = emptyList(),
    val recommendedStatus: String? = null,
    val statusReason: String? = null,
    val detectedType: String? = null,
    val affectedComponents: List<String> = emptyList(),
    val inspectionSaved: Boolean = false,
    val ocrText: String = "",
    val ocrConfidence: Float? = null,
    val detectedPumpModel: String? = null,
    val detectedAssetId: String? = null,
    val inspectionDetails: InspectionDetails? = null,
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
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, loginError = null, password = "", statusMessage = if (remote?.accepted == true) "Вход подтверждён" else "Локальный вход выполнен") } }
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
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, loginError = null, statusMessage = "Личный кабинет создан на устройстве") } }
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
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, localVerificationCode = null, loginError = null, statusMessage = "Почта подтверждена, кабинет сохранён") } }
        }
    }
    fun completeOfflineRegistration() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val user = store.register(state.pendingEmail.ifBlank { state.email.trim() }, state.password, state.pendingName.ifBlank { state.name.trim() }, state.pendingPosition)
            if (user == null) _uiState.update { it.copy(loginError = "Пользователь уже сохранён на устройстве") }
            else { persistSession(user); _uiState.update { it.copy(user = user, screen = AppScreen.CATALOG, registration = false, loginError = null, statusMessage = "Кабинет создан на устройстве") } }
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
    fun startInspection() = _uiState.update { it.copy(screen = AppScreen.SCAN, selectedEquipment = null, selectedComponent = null, recognitionCandidates = emptyList(), boxes = emptyList(), affectedComponents = emptyList(), previewBitmap = null, detectedType = null, ocrText = "", ocrConfidence = null, detectedPumpModel = null, detectedAssetId = null) }
    fun startComponentInspection(component: String) = _uiState.update {
        it.copy(
            screen = AppScreen.SCAN,
            selectedComponent = component,
            boxes = emptyList(),
            affectedComponents = emptyList(),
            visualCategory = "INSUFFICIENT_DATA",
            summary = "Готов к проверке узла «$component»",
            recommendation = "Наведите камеру на выбранный узел и сделайте снимок",
            statusMessage = "Выбран узел: $component"
        )
    }
    fun openHistory() = _uiState.update { it.copy(screen = AppScreen.HISTORY, allInspections = store.allInspections(), maintenance = store.maintenance()) }
    fun openInspectionDetails(inspectionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(inspectionDetails = store.inspectionDetails(inspectionId)) }
        }
    }
    fun closeInspectionDetails() = _uiState.update { it.copy(inspectionDetails = null) }
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
            val path = store.backupDatabaseToDownloads()
            _events.emit(if (path == null) "Не удалось создать резервную копию" else "Резервная копия сохранена в папке Загрузки/KST Energo: " + path.substringAfterLast('/'))
        }
    }
    fun exportFeedback() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.user?.role != "admin") { _events.emit("Требуются права администратора"); return@launch }
            val path = store.exportFeedbackToDownloads()
            _events.emit(if (path == null) "Очередь данных пуста или не выгружена" else "Данные сохранены в папке Загрузки/KST Energo: " + path.substringAfterLast('/'))
        }
    }

    fun deleteInspection(inspectionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (store.deleteInspection(inspectionId)) {
                val current = _uiState.value
                _uiState.update {
                    it.copy(
                        allInspections = store.allInspections(),
                        inspections = it.selectedEquipment?.let { selected -> store.inspections(selected.id) }.orEmpty(),
                        statusMessage = "Проверка удалена из журнала"
                    )
                }
                _events.emit("Проверка №$inspectionId удалена")
            } else _events.emit("Проверка не найдена")
        }
    }
    fun chooseRecognitionCandidate(item: EquipmentRecord) = _uiState.update {
        it.copy(
            selectedEquipment = item,
            selectedComponent = null,
            screen = AppScreen.RESULT,
            inspections = store.inspections(item.id),
            recognitionCandidates = emptyList(),
            summary = "Объект выбран: ${item.name}. Проверьте рекомендацию программы и паспорт.",
            recommendation = "Подтвердите статус после визуальной проверки узлов."
        )
    }
    fun createEquipmentFromRecognition(typeLabel: String, cover: Bitmap?) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для добавления объекта") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val displayType = when (typeLabel.lowercase()) {
                "pump" -> "Насос"
                "motor" -> "Электродвигатель"
                "gearbox" -> "Редуктор"
                "fan" -> "Вентилятор"
                "valve" -> "Клапан / задвижка"
                "pipe", "pipeline" -> "Трубопровод"
                "tank" -> "Резервуар"
                "control_cabinet" -> "Шкаф управления"
                "sensor" -> "Датчик КИПиА"
                else -> typeLabel.ifBlank { "Пользовательский объект" }
            }
            val id = store.addCustomEquipment("Новый объект — $displayType", displayType, "", "Не указано", "Параметры не заполнены", cover)
            val item = store.equipment().firstOrNull { it.id == id }
            _uiState.update { it.copy(equipment = store.equipment(), selectedEquipment = item, recognitionCandidates = emptyList(), screen = if (item == null) AppScreen.CATALOG else AppScreen.RESULT) }
            _events.emit(if (item == null) "Не удалось создать объект" else "Новый объект создан из фотографии")
        }
    }
    fun chooseEquipment(item: EquipmentRecord) = _uiState.update { it.copy(selectedEquipment = item, selectedComponent = null, screen = AppScreen.SCAN, boxes = emptyList(), affectedComponents = emptyList(), previewBitmap = null, inspections = store.inspections(item.id), visualCategory = "INSUFFICIENT_DATA", summary = "Готов к предварительному анализу: ${item.name}", recommendation = "Сделайте фото объекта в центре кадра и запустите анализ") }
    fun cameraCaptureFailed() { viewModelScope.launch { _events.emit("Не удалось получить кадр. Проверьте разрешение и повторите съёмку.") } }
    private fun refreshEquipmentSelection(id: String) {
        val refreshed = store.equipment()
        _uiState.update { current ->
            current.copy(
                equipment = refreshed,
                selectedEquipment = refreshed.firstOrNull { it.id == id } ?: current.selectedEquipment,
                inspections = current.selectedEquipment?.let { selected -> store.inspections(selected.id) } ?: current.inspections
            )
        }
    }
    fun addEquipmentPhoto(equipmentId: String, bitmap: Bitmap, replacePhotoId: Long? = null) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для изменения фотографий") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = store.addEquipmentPhoto(equipmentId, bitmap, replacePhotoId = replacePhotoId)
            if (saved == null) _events.emit("Можно хранить не более пяти фотографий объекта")
            else { refreshEquipmentSelection(equipmentId); _events.emit("Фотография паспорта сохранена") }
        }
    }
    fun deleteEquipmentPhoto(photoId: Long, equipmentId: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.deleteEquipmentPhoto(photoId)) { refreshEquipmentSelection(equipmentId); _events.emit("Фотография удалена") }
        }
    }
    fun setPrimaryPhoto(photoId: Long, equipmentId: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.setPrimaryPhoto(photoId)) { refreshEquipmentSelection(equipmentId); _events.emit("Главная фотография обновлена") }
        }
    }
    fun updateComponentStatus(componentId: String, equipmentId: String, status: String, comment: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (store.updateComponent(componentId, status, comment)) { refreshEquipmentSelection(equipmentId); _events.emit("Состояние узла сохранено") }
        }
    }
    fun addCustomComponent(equipmentId: String, name: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.addComponent(equipmentId, name) != null) {
                refreshEquipmentSelection(equipmentId); _events.emit("Пользовательский узел добавлен")
            } else _events.emit("Введите название узла")
        }
    }
    fun renameCustomComponent(componentId: String, equipmentId: String, name: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.renameComponent(componentId, name)) { refreshEquipmentSelection(equipmentId); _events.emit("Название узла изменено") }
        }
    }
    fun deleteCustomComponent(componentId: String, equipmentId: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.deleteComponent(componentId)) { refreshEquipmentSelection(equipmentId); _events.emit("Пользовательский узел удалён") }
        }
    }
    fun applyRecommendedStatus() {
        val state = _uiState.value
        val item = state.selectedEquipment ?: return
        val status = state.recommendedStatus ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (store.applyRecommendedStatus(item.id, status)) {
                val savedId = if (state.inspectionSaved) null else saveInspectionBundle(state, item)
                refreshEquipmentSelection(item.id)
                _uiState.update { current -> current.copy(statusMessage = if (state.inspectionSaved || savedId != null) "Статус принят, акт сохранён" else "Статус принят; акт не удалось сохранить", inspectionSaved = state.inspectionSaved || savedId != null, inspections = store.inspections(item.id), allInspections = store.allInspections(), feedbackCount = store.feedbackCount()) }
                _events.emit("Статус объекта обновлён")
            }
        }
    }
    /** Keep the passport status unchanged but save the inspection and the
     * engineer's decision alongside the model recommendation. */
    fun keepCurrentStatus() {
        val state = _uiState.value
        val item = state.selectedEquipment ?: return
        if (state.isSaving || state.inspectionSaved) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val savedId = saveInspectionBundle(state, item)
            _uiState.update { current -> current.copy(isSaving = false, inspectionSaved = savedId != null, inspections = store.inspections(item.id), allInspections = store.allInspections(), feedbackCount = store.feedbackCount(), statusMessage = if (savedId != null) "Текущий статус оставлен, акт сохранён" else "Не удалось сохранить акт") }
            _events.emit(if (savedId != null) "Решение сохранено без изменения статуса" else "Ошибка локальной базы. Акт не сохранён.")
        }
    }
    fun editPassportAndStatus() {
        val item = _uiState.value.selectedEquipment ?: return
        _uiState.update { it.copy(screen = AppScreen.DETAIL, inspections = store.inspections(item.id)) }
    }
    fun backToCatalog() = _uiState.update { it.copy(screen = AppScreen.CATALOG, selectedEquipment = null, boxes = emptyList(), affectedComponents = emptyList(), previewBitmap = null, detectedType = null) }
    fun addCustomEquipment(name: String, type: String, tag: String, location: String, specs: String, cover: Bitmap? = null) {
        if (type.isBlank()) return
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для изменения каталога") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.addCustomEquipment(name.trim().ifBlank { "Новый объект — ${type.trim()}" }, type.trim(), tag.trim(), location.trim().ifBlank { "Не указано" }, specs.trim().ifBlank { "Параметры не заполнены" }, cover)
            _uiState.update { it.copy(equipment = store.equipment(), statusMessage = "Объект добавлен в локальный каталог") }
            _events.emit("Пользовательский объект сохранён")
        }
    }
    fun updateEquipment(id: String, name: String, tag: String, location: String, specs: String, status: String) {
        if (_uiState.value.user?.role !in setOf("admin", "engineer")) {
            viewModelScope.launch { _events.emit("Войдите как инженер или администратор для изменения каталога") }
            return
        }
        val current = _uiState.value.equipment.firstOrNull { it.id == id }
        val safeName = name.trim().ifBlank { "Новый объект — " + (current?.typeLabel ?: "оборудование") }
        val safeTag = tag.trim().ifBlank { "LOCAL-" + System.currentTimeMillis() }
        viewModelScope.launch(Dispatchers.IO) {
            if (store.updateEquipment(id, safeName, safeTag, location.trim().ifBlank { "Не указано" }, specs.trim().ifBlank { "Параметры не заполнены" }, status)) {
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
        if (_uiState.value.isLoading) return
        val selected = _uiState.value.selectedEquipment
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Локальный анализ изображения…") }
            val sourceBitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull()
            val bitmap = sourceBitmap?.let(::cropInspectionRegion)
            val analysisWidth = bitmap?.width ?: width.coerceAtLeast(1)
            val analysisHeight = bitmap?.height ?: height.coerceAtLeast(1)
            val usableFrame = bitmap?.let(detector::isUsableFrame) == true
            val ocr = if (bitmap != null) {
                try { readEquipmentPlate(bitmap) } catch (_: Exception) { OcrReading() }
            } else OcrReading()
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
                "pipeline" to "Трубопровод",
                "control_cabinet" to "Шкаф управления",
                "sensor" to "Датчик КИПиА",
                "custom" to "Пользовательский объект"
            )
            val selectedIsSupported = selected?.type in supportedTypes.keys
            val specializedModelTypes = setOf("pump", "motor", "valve", "pipe")
            val hasSpecializedModel = selected?.type in specializedModelTypes
            val expectedLabel = selected?.type?.let { supportedTypes[it] }
            val rawMatchingEquipmentDetection = equipmentDetections.firstOrNull { detection ->
                expectedLabel?.let { expected -> detection.label == expected || (selected?.type == "valve" && detection.label == "Клапан / задвижка") } == true
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
            val bestEquipmentDetection = equipmentDetections
                .filter { detection -> areaRatio(detection) in 0.04f..0.78f && detection.confidence >= 0.45f }
                .maxByOrNull { it.confidence }
            val equipmentDetection = matchingEquipmentDetection ?: if (selected == null) bestEquipmentDetection else null
            val detectedTypeKey = equipmentDetection?.label?.let { label ->
                supportedTypes.entries.firstOrNull { it.value == label }?.key
                    ?: if (label == "Клапан / задвижка") "valve" else null
            }
            val normalizedOcr = ocr.normalizedText
            fun ocrMatches(item: EquipmentRecord): Boolean {
                if (normalizedOcr.isBlank()) return false
                val values = listOf(item.id, item.name, item.model, item.tag, item.serial, item.location)
                return values.any { value ->
                    val normalized = value.uppercase().replace('О', 'O').replace('Р', 'P').replace('А', 'A').replace('В', 'B').replace('С', 'C').replace('Т', 'T').replace('Х', 'X')
                    normalized.isNotBlank() && normalizedOcr.contains(normalized, ignoreCase = true)
                }
            }
            val ocrMatches = store.equipment().filter { ocrMatches(it) }
            val candidates = if (selected == null && detectedTypeKey != null) {
                val pool = if (ocrMatches.isNotEmpty()) ocrMatches else store.equipment().filter { it.type == detectedTypeKey }
                pool.map { item -> RecognitionCandidate(item, if (ocrMatches.contains(item)) .98f else (equipmentDetection?.confidence ?: 0f), detectedTypeKey, ocr.model, if (ocrMatches.contains(item)) "Совпадение номера или модели" else "Совпадение типа") }
            } else emptyList()
            val detectedAsset = ocrMatches.firstOrNull()?.id
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
                selected == null && equipmentDetection == null -> "INSUFFICIENT_DATA"
                selected != null && !selectedIsSupported -> "INSUFFICIENT_DATA"
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
            } else if (selected == null && detectedObjects.isNotBlank()) {
                "В кадре найдены объекты: " + detectedObjects + ". Выберите нужный объект в каталоге."
            } else if (selected != null && !selectedIsSupported) {
                "Тип оборудования не найден в каталоге."
            } else if (selected != null && !hasSpecializedModel) {
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
                selected == null -> "Выберите найденный объект в каталоге или создайте новый паспорт."
                !selectedIsSupported -> "Выберите объект из каталога или добавьте его через кабинет администратора."
                !hasSpecializedModel -> "Проверьте компоненты по чек-листу и сохраните результат инженера."
                equipmentDetection == null -> "Повторите кадрирование, улучшите освещение или подтвердите объект вручную."
                !selectedTypeMatches -> "Проверьте выбранный объект, затем повторите кадрирование."
                else -> "Выполните визуальную проверку, подтвердите статус инженером и сохраните акт."
            }
            val boxes = buildList {
                equipmentDetection?.let { detection -> add(ArBoundingBox(detection.x1, detection.y1, detection.x2, detection.y2, detection.label, "CHECK_RECOMMENDED", detection.confidence)) }
                visibleDetections.filter { it.label in defectLabels }.distinctBy { it.label }.forEach { detection ->
                    val equipmentType = selected?.type ?: detectedTypeKey
                    add(ArBoundingBox(detection.x1, detection.y1, detection.x2, detection.y2, detection.label, "POSSIBLE_DEFECT", detection.confidence, defectComponents(detection.label, equipmentType).firstOrNull()))
                }
            }
            val affectedComponents = visibleDetections.filter { it.label in defectLabels }.distinctBy { it.label }.flatMap { defectComponents(it.label, selected?.type ?: detectedTypeKey) }.distinct()
            // A model finding is a recommendation for the engineer. Persist it as
            // “Требует проверки” on the selected object's matching nodes, while
            // preserving a previously confirmed NORMAL/DEFECT decision.
            if (selected != null && affectedComponents.isNotEmpty()) {
                selected.componentRecords
                    .filter { it.name in affectedComponents && it.status == "UNKNOWN" }
                    .forEach { component ->
                        store.updateComponent(component.id, "CHECK_RECOMMENDED", "Рекомендовано моделью по результату анализа")
                    }
            }
            val refreshedSelected = selected?.id?.let { id -> store.equipment().firstOrNull { it.id == id } }
            val resultWidth = analysisWidth
            val resultHeight = analysisHeight
            val suggestedStatus = when {
                localizedDefect?.label == "След перегрева" && localizedDefect.confidence >= 0.85f -> "CRITICAL"
                localizedDefect?.label == "Возможная утечка" && localizedDefect.confidence >= 0.85f -> "CRITICAL"
                localizedDefect != null || defect != null -> "CHECK_RECOMMENDED"
                else -> "CHECK_RECOMMENDED"
            }
            val modelSummary = ocr.model?.let { " Визуальная модель по шильдику: $it." } ?: ""
            _uiState.update { it.copy(screen = AppScreen.RESULT, isLoading = false, statusMessage = if (equipmentDetection != null) "Тип объекта распознан; проверьте выделенные узлы" else "Недостаточно данных для автоматической оценки", selectedEquipment = refreshedSelected ?: it.selectedEquipment, inspections = refreshedSelected?.let { item -> store.inspections(item.id) } ?: it.inspections, visualCategory = category, predictedCategory = category, confidence = confidence, summary = summary + modelSummary, recommendation = recommendation, boxes = boxes, previewBitmap = bitmap, sourceImageWidth = resultWidth, sourceImageHeight = resultHeight, recognitionCandidates = candidates, detectedType = detectedTypeKey, affectedComponents = affectedComponents, recommendedStatus = suggestedStatus, statusReason = localizedDefect?.label ?: "Требуется подтверждение инженера", ocrText = ocr.rawText, ocrConfidence = ocr.confidence.takeIf { it > 0f }, detectedPumpModel = ocr.model, detectedAssetId = detectedAsset) }
        }
    }
    fun confirmVisualStatus(category: String) {
        val actor = if (_uiState.value.user == null) "Пользователь" else "Инженер"
        val summary = when (category) { "NO_VISIBLE_PROBLEM" -> "$actor подтвердил: видимых проблем не обнаружено"; "POSSIBLE_DEFECT" -> "$actor зафиксировал признаки возможного дефекта"; else -> "$actor отметил необходимость дополнительной проверки" }
        val recommendation = when (category) { "NO_VISIBLE_PROBLEM" -> "Продолжить эксплуатацию и повторить проверку по регламенту"; "POSSIBLE_DEFECT" -> "Проверить компонент и выполнить необходимые работы"; else -> "Запланировать дополнительную проверку компонента" }
        val acceptedStatus = when (category) {
            "NO_VISIBLE_PROBLEM" -> "NORMAL"
            "POSSIBLE_DEFECT" -> "CRITICAL"
            else -> "CHECK_RECOMMENDED"
        }
        _uiState.update { it.copy(visualCategory = category, summary = summary, recommendation = recommendation, recommendedStatus = acceptedStatus, statusReason = "Решение подтверждено пользователем", inspectionSaved = false) }
    }
    private fun saveInspectionBundle(state: ArUiState, item: EquipmentRecord): Long? {
        val user = state.user
        val inspectionId = runCatching { store.saveInspection(item.id, user?.id, state.visualCategory, state.confidence, state.summary, state.recommendation, state.previewBitmap, state.recommendedStatus ?: "OPEN") }.getOrNull() ?: return null
        runCatching {
            store.saveFeedback(inspectionId, item.id, user?.id, state.predictedCategory ?: state.visualCategory, state.visualCategory, state.selectedComponent, if (user == null) "Подтверждено пользователем" else "Подтверждено инженером")
            item.componentRecords.filter { it.name in state.affectedComponents || it.name == state.selectedComponent }.forEach { component ->
                store.saveComponentInspection(inspectionId, component.id, item.id, component.status, component.comment)
            }
        }
        return inspectionId
    }
    fun saveInspection() {
        val state = _uiState.value; val item = state.selectedEquipment ?: return; val user = state.user
        if (state.isSaving || state.inspectionSaved) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val inspectionId = saveInspectionBundle(state, item)
            val saved = inspectionId != null
            _uiState.update {
                it.copy(
                    isSaving = false,
                    inspectionSaved = saved,
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
