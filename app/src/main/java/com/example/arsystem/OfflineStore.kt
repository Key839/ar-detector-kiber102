package com.example.arsystem

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

data class EquipmentRecord(
    val id: String,
    val type: String,
    val typeLabel: String,
    val name: String,
    val model: String,
    val tag: String,
    val serial: String,
    val location: String,
    val hours: Double,
    val toLimit: Double,
    val status: String,
    val specs: String,
    val components: List<String>,
    val coverPath: String? = null,
    /** All user supplied passport photographs, ordered for display. */
    val photos: List<EquipmentPhoto> = emptyList(),
    /** Component rows with persisted status/comment metadata. */
    val componentRecords: List<ComponentRecord> = emptyList()
)

data class EquipmentPhoto(
    val id: Long,
    val equipmentId: String,
    val path: String,
    val kind: String = "passport",
    val sortOrder: Int = 0,
    val isPrimary: Boolean = false,
    val createdAt: String = ""
)

data class ComponentRecord(
    val id: String,
    val equipmentId: String,
    val name: String,
    val status: String = "UNKNOWN",
    val comment: String = "",
    val isCustom: Boolean = false
)

data class ComponentInspection(
    val id: Long,
    val inspectionId: Long?,
    val componentId: String,
    val equipmentId: String,
    val status: String,
    val comment: String,
    val createdAt: String,
    val componentName: String = ""
)

data class LocalUser(val id: Long, val email: String, val name: String, val role: String, val position: String = "Инженер по эксплуатации")
data class InspectionRecord(
    val id: Long,
    val equipmentId: String = "",
    val equipmentName: String = "",
    val category: String,
    val summary: String,
    val recommendation: String,
    val createdAt: String,
    val imagePath: String? = null,
    val confidence: Float? = null,
    val userId: Long? = null,
    val status: String = "",
    val detectedEquipmentType: String? = null,
    val detectedPumpModel: String? = null,
    val detectedAssetId: String? = null,
    val ocrText: String = ""
)

data class InspectionDetails(
    val inspection: InspectionRecord,
    val components: List<ComponentInspection> = emptyList()
)
data class MaintenanceRecord(val equipmentId: String, val equipmentName: String, val dueLabel: String, val status: String)
data class FeedbackRecord(
    val id: Long,
    val inspectionId: Long?,
    val equipmentId: String,
    val userId: Long?,
    val predictedCategory: String,
    val correctedCategory: String,
    val component: String?,
    val comment: String,
    val createdAt: String,
    val exported: Boolean
)

class OfflineStore(private val context: Context) : SQLiteOpenHelper(context, "kst_offline.db", null, 16) {
    private val imageDir = File(context.filesDir, "inspection_images").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "equipment_covers").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, position TEXT NOT NULL DEFAULT 'Инженер по эксплуатации', created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE equipment(id TEXT PRIMARY KEY, type TEXT NOT NULL, type_label TEXT NOT NULL, name TEXT NOT NULL, model TEXT NOT NULL, tag TEXT NOT NULL, serial TEXT NOT NULL, location TEXT NOT NULL, hours REAL NOT NULL, to_limit REAL NOT NULL, status TEXT NOT NULL, specs TEXT NOT NULL, cover_path TEXT)")
        db.execSQL("CREATE TABLE components(id TEXT PRIMARY KEY, equipment_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, comment TEXT NOT NULL DEFAULT '', is_custom INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE equipment_photos(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, path TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'passport', sort_order INTEGER NOT NULL DEFAULT 0, is_primary INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE component_inspections(id INTEGER PRIMARY KEY AUTOINCREMENT, inspection_id INTEGER, component_id TEXT NOT NULL, equipment_id TEXT NOT NULL, status TEXT NOT NULL, comment TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE inspections(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, user_id INTEGER, created_at TEXT NOT NULL, category TEXT NOT NULL, confidence REAL, summary TEXT NOT NULL, recommendation TEXT NOT NULL, image_path TEXT, status TEXT NOT NULL DEFAULT 'OPEN', detected_equipment_type TEXT, detected_pump_model TEXT, detected_asset_id TEXT, ocr_text TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE maintenance_events(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, created_at TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE feedback_queue(id INTEGER PRIMARY KEY AUTOINCREMENT, inspection_id INTEGER, equipment_id TEXT NOT NULL, user_id INTEGER, predicted_category TEXT NOT NULL, corrected_category TEXT NOT NULL, component TEXT, comment TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, exported INTEGER NOT NULL DEFAULT 0)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE inspections ADD COLUMN image_path TEXT")
        if (oldVersion < 3) db.execSQL("CREATE TABLE IF NOT EXISTS maintenance_events(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, created_at TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL)")
        if (oldVersion < 4) seedExtendedEquipment(db)
        if (oldVersion < 6) seedAdditionalPumps(db)
        if (oldVersion < 7) db.execSQL("ALTER TABLE users ADD COLUMN position TEXT NOT NULL DEFAULT 'Инженер по эксплуатации'")
        if (oldVersion < 8) db.execSQL("CREATE TABLE IF NOT EXISTS feedback_queue(id INTEGER PRIMARY KEY AUTOINCREMENT, inspection_id INTEGER, equipment_id TEXT NOT NULL, user_id INTEGER, predicted_category TEXT NOT NULL, corrected_category TEXT NOT NULL, component TEXT, comment TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, exported INTEGER NOT NULL DEFAULT 0)")
        if (oldVersion < 9) db.execSQL("ALTER TABLE equipment ADD COLUMN cover_path TEXT")
        if (oldVersion < 10) {
            // Account reset for the guest-first release. Historical inspections
            // remain available, but no longer point to deleted user rows.
            db.execSQL("UPDATE inspections SET user_id=NULL")
            db.execSQL("UPDATE feedback_queue SET user_id=NULL")
            db.execSQL("DELETE FROM users")
            seedUsers(db)
        }
        if (oldVersion < 11) {
            // Keep custom and universal catalog entries. Older builds used to
            // remove them here when the CV model exposed only four classes.
        }
        if (oldVersion < 12) {
            runCatching { db.execSQL("ALTER TABLE components ADD COLUMN comment TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE components ADD COLUMN is_custom INTEGER NOT NULL DEFAULT 0") }
            db.execSQL("CREATE TABLE IF NOT EXISTS equipment_photos(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, path TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'passport', sort_order INTEGER NOT NULL DEFAULT 0, is_primary INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS component_inspections(id INTEGER PRIMARY KEY AUTOINCREMENT, inspection_id INTEGER, component_id TEXT NOT NULL, equipment_id TEXT NOT NULL, status TEXT NOT NULL, comment TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)")
            // Preserve the old single cover as the primary gallery image.
            db.execSQL("INSERT INTO equipment_photos(equipment_id,path,kind,sort_order,is_primary,created_at) SELECT id,cover_path,'passport',0,1,? FROM equipment WHERE cover_path IS NOT NULL AND trim(cover_path) <> '' AND NOT EXISTS (SELECT 1 FROM equipment_photos p WHERE p.equipment_id=equipment.id)", arrayOf(System.currentTimeMillis().toString()))
            ensureComponentTemplates(db)
        }
        if (oldVersion < 13) {
            // Older releases temporarily filtered the catalog to four CV classes.
            // Restore the built-in records and keep all equipment types available.
            restoreUniversalEquipment(db)
            ensureComponentTemplates(db)
        }
        if (oldVersion < 14) {
            // Re-run synchronization for installations upgraded from any
            // previous schema without deleting user-created equipment.
            restoreUniversalEquipment(db)
            ensureComponentTemplates(db)
        }
        if (oldVersion < 15) {
            runCatching { db.execSQL("ALTER TABLE inspections ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN'") }
        }
        if (oldVersion < 16) {
            runCatching { db.execSQL("ALTER TABLE inspections ADD COLUMN detected_equipment_type TEXT") }
            runCatching { db.execSQL("ALTER TABLE inspections ADD COLUMN detected_pump_model TEXT") }
            runCatching { db.execSQL("ALTER TABLE inspections ADD COLUMN detected_asset_id TEXT") }
            runCatching { db.execSQL("ALTER TABLE inspections ADD COLUMN ocr_text TEXT NOT NULL DEFAULT ''") }
        }
    }

    /**
     * Some field phones still have a database created by an intermediate
     * release whose schema number is higher than the APK that was copied to
     * the phone. Android otherwise throws before Compose can render anything
     * ("Can't downgrade database"). The schema additions are backward
     * compatible, so keep the on-device database at its newer version and let
     * the older binary use the columns it knows. The next APK upgrade will run
     * the normal onUpgrade path.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion > newVersion) {
            db.version = oldVersion
            return
        }
        super.onDowngrade(db, oldVersion, newVersion)
    }

    private fun seed(db: SQLiteDatabase) {
        seedUsers(db)
        seedEquipment(db)
        ensureComponentTemplates(db)
    }

    private fun seedUsers(db: SQLiteDatabase) {
        val now = System.currentTimeMillis().toString()
        val users = listOf(
            arrayOf("admin@kst-energo.ru", hashPassword("kiber102"), "Администратор КСТ Энерго", "admin", "Администратор", now),
            arrayOf("engineer@kst-energo.ru", hashPassword("kiber102"), "Инженер КСТ Энерго", "engineer", "Инженер-механик", now)
        )
        users.forEach { db.execSQL("INSERT INTO users(email,password_hash,name,role,position,created_at) VALUES(?,?,?,?,?,?)", it) }
    }

    private fun seedEquipment(db: SQLiteDatabase) {
        val equipment = listOf(
            arrayOf("AMS-1", "pump", "Насос", "Насос №1 AMS PCP60S12/3", "AMS PCP60S12/3", "860-Р-0201-А", "21031004-1", "Очистные сооружения", 3920, 4000, "SERVICE_SOON", "Подача 18 м³/ч; давление до 10 бар; 260 об/мин"),
            arrayOf("MTR-1", "motor", "Электродвигатель", "ELDIN BRA160M4F", "BRA160M4FБУ1", "MTR-0201", "230201068", "Машинный зал", 3920, 20000, "NORMAL", "11 кВт; 380 В; 23 А; 1Ex d IIB T4 Gb"),
            arrayOf("GBX-1", "gearbox", "Редуктор", "WANSHSIN WXRF87", "WXRF87-AM160-5.30", "GBX-0201", "SAL023020084", "Очистные сооружения", 3920, 12000, "SERVICE_SOON", "Передаточное отношение 5.30; 910 Н·м; VG220"),
            arrayOf("FAN-1", "fan", "Вентилятор", "Вентилятор вытяжной ВЦ", "ВЦ 14-46", "FAN-01", "FAN-001", "Цех водоподготовки", 7400, 15000, "NORMAL", "Производительность 12000 м³/ч; 1450 об/мин"),
            arrayOf("VLV-1", "valve", "Задвижка", "Задвижка клиновая", "30с41нж", "VLV-0201", "VLV-001", "Трубопроводная галерея", 2100, 10000, "CHECK_RECOMMENDED", "DN150; PN16; ручной привод; вода"),
            arrayOf("TNK-1", "tank", "Резервуар", "Резервуар-усреднитель", "РВС-100", "TNK-0601", "TNK-001", "Резервуары-усреднители", 8120, 10000, "CHECK_RECOMMENDED", "100 м³; атмосферный; сталь 09Г2С")
        )
        equipment.forEach { db.execSQL("INSERT INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", it) }
        val components = listOf(
            arrayOf("AMS-1-motor", "AMS-1", "Электродвигатель", "UNKNOWN"), arrayOf("AMS-1-seal", "AMS-1", "Торцевое уплотнение", "CHECK_RECOMMENDED"),
            arrayOf("MTR-1-bearings", "MTR-1", "Подшипники", "UNKNOWN"), arrayOf("GBX-1-oil", "GBX-1", "Масло VG220", "SERVICE_SOON"),
            arrayOf("VLV-1-stem", "VLV-1", "Шток и сальник", "UNKNOWN"), arrayOf("TNK-1-shell", "TNK-1", "Корпус и сварные швы", "CHECK_RECOMMENDED")
        )
        components.forEach { db.execSQL("INSERT INTO components(id,equipment_id,name,status) VALUES(?,?,?,?)", it) }
        seedExtendedEquipment(db)
        seedAdditionalPumps(db)
    }

    private fun seedExtendedEquipment(db: SQLiteDatabase) {
        val extended = listOf(
            arrayOf("PIP-1", "pipe", "Трубопровод", "Трубопровод технологический DN150", "Сталь 20 DN150", "PIP-0201", "PIPE-001", "Трубопроводная галерея", 2100, 30000, "CHECK_RECOMMENDED", "DN150; PN16; вода; сталь 20"),
            arrayOf("CAB-1", "control_cabinet", "Шкаф управления", "Шкаф управления насосным агрегатом", "КШУ-0201", "CAB-0201", "CAB-001", "Машинный зал", 3920, 20000, "NORMAL", "380 В; IP54; ПЛК и частотный привод"),
            arrayOf("SNS-1", "sensor", "Датчик КИПиА", "Датчик температуры Pt100", "ЭЛЕМЕР ПТ 0304Ex", "SNS-0201", "SNS-001", "Очистные сооружения", 3920, 15000, "NORMAL", "-50…+180 °C; Ex; класс A")
        )
        extended.forEach { row ->
            db.execSQL("INSERT OR IGNORE INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
            db.execSQL("INSERT OR IGNORE INTO components(id,equipment_id,name,status) VALUES(?,?,?,?)", arrayOf(row[0].toString() + "-main", row[0], row[2], "UNKNOWN"))
        }
    }

    private fun seedAdditionalPumps(db: SQLiteDatabase) {
        val pumps = listOf(
            arrayOf("AMS-2", "Насос №2 AMS PCP60S12/3", "AMS PCP60S12/3", "860-Р-0201-В", "21031004-2", "Очистные сооружения", 2890, 3000, "Подача 18 м³/ч; давление до 10 бар; 260 об/мин"),
            arrayOf("AMS-3", "Насос №3 AMS PCP60S12/3", "AMS PCP60S12/3", "860-Р-0601-А", "21031004-3", "Резервуары-усреднители", 7960, 8000, "Подача 18 м³/ч; давление до 10 бар; 260 об/мин"),
            arrayOf("AMS-4", "Насос №4 AMS PCP60S12/3", "AMS PCP60S12/3", "860-Р-0601-В", "21031004-4", "Резервуары-усреднители", 1540, 3000, "Подача 18 м³/ч; давление до 10 бар; 260 об/мин"),
            arrayOf("WILO-IL", "Циркуляционный насос Wilo CronoLine-IL 65/160-5,5/2", "Wilo CronoLine-IL 65/160-5,5/2", "IL-65/160", "Арт. 2786107", "Цех водоподготовки и теплоснабжения", 14890, 15000, "Подача 47 м³/ч; напор 31,7 м; давление 16 бар")
        )
        pumps.forEach { row ->
            val remaining = (row[7] as Number).toDouble() - (row[6] as Number).toDouble()
            val status = if (remaining <= 120) "SERVICE_SOON" else "NORMAL"
            db.execSQL("INSERT OR IGNORE INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(row[0], "pump", "Насос", row[1], row[2], row[3], row[4], row[5], row[6], row[7], status, row[8]))
            val id = row[0].toString()
            listOf("Двигатель", "Подшипниковый узел", "Уплотнение").forEachIndexed { index, component ->
                db.execSQL("INSERT OR IGNORE INTO components(id,equipment_id,name,status) VALUES(?,?,?,?)",
                    arrayOf("$id-component-$index", id, component, "UNKNOWN"))
            }
        }
    }

    /** Ensure every catalog object has a useful, type-specific inspection checklist. */
    private fun ensureComponentTemplates(db: SQLiteDatabase, onlyEquipmentId: String? = null) {
        val cursor = if (onlyEquipmentId == null) {
            db.rawQuery("SELECT id,type FROM equipment", null)
        } else {
            db.rawQuery("SELECT id,type FROM equipment WHERE id=?", arrayOf(onlyEquipmentId))
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                val equipmentId = c.getString(0)
                val type = c.getString(1)
                val names = when (type) {
                    "pump" -> listOf("Корпус", "Электродвигатель", "Подшипники", "Торцевое уплотнение", "Муфта", "Вал", "Фланцы", "Основание и анкерные болты", "Кабельная коробка", "Утечки", "Ржавчина/коррозия")
                    "motor" -> listOf("Корпус", "Вентиляция", "Подшипники", "Вал", "Муфта", "Клеммная коробка", "Крепление", "След перегрева", "Ржавчина/коррозия")
                    "valve" -> listOf("Корпус", "Шток", "Сальник", "Маховик или привод", "Фланцы", "Прокладки", "Утечка", "Ржавчина/коррозия")
                    "pipe" -> listOf("Труба", "Фланцы", "Сварные швы", "Опоры", "Изоляция", "Арматура", "Утечка", "Ржавчина/коррозия")
                    "gearbox" -> listOf("Корпус", "Зубчатая передача", "Подшипники", "Масляная ванна", "Муфта", "Вал", "Сальники", "Утечки", "Ржавчина/коррозия")
                    "fan" -> listOf("Кожух", "Рабочее колесо", "Двигатель", "Ремень/муфта", "Подшипники", "Крепление", "Вибрация", "Ржавчина/коррозия")
                    "tank" -> listOf("Стенка корпуса", "Днище", "Сварные швы", "Люк и патрубки", "Арматура", "Опоры", "Утечки", "Ржавчина/коррозия")
                    "control_cabinet" -> listOf("Корпус шкафа", "Дверь и уплотнение", "Клеммные соединения", "Автоматика", "Вентиляция", "Кабельные вводы", "След перегрева", "Ржавчина/коррозия")
                    "sensor" -> listOf("Корпус датчика", "Чувствительный элемент", "Кабель", "Клеммы", "Крепление", "Герметичность", "След перегрева", "Ржавчина/коррозия")
                    else -> listOf("Основной узел", "Крепление", "Соединения", "Утечки", "Ржавчина/коррозия")
                }
                val existingNames = buildSet {
                    db.rawQuery("SELECT name FROM components WHERE equipment_id=?", arrayOf(equipmentId)).use { existing ->
                        while (existing.moveToNext()) add(existing.getString(0))
                    }
                }
                names.forEachIndexed { index, name ->
                    if (name in existingNames) return@forEachIndexed
                    val id = "$equipmentId-template-$index"
                    db.execSQL("INSERT OR IGNORE INTO components(id,equipment_id,name,status,comment,is_custom) VALUES(?,?,?,'UNKNOWN','',0)", arrayOf(id, equipmentId, name))
                }
            }
        }
    }

    /** Re-add catalog records that older migrations removed when the app supported only four CV classes. */
    private fun restoreUniversalEquipment(db: SQLiteDatabase) {
        val rows = listOf(
            arrayOf("GBX-1", "gearbox", "Редуктор", "WANSHSIN WXRF87", "WXRF87-AM160-5.30", "GBX-0201", "SAL023020084", "Очистные сооружения", 3920, 12000, "SERVICE_SOON", "Передаточное отношение 5.30; 910 Н·м; VG220"),
            arrayOf("FAN-1", "fan", "Вентилятор", "Вентилятор вытяжной ВЦ", "ВЦ 14-46", "FAN-01", "FAN-001", "Цех водоподготовки", 7400, 15000, "NORMAL", "Производительность 12000 м³/ч; 1450 об/мин"),
            arrayOf("TNK-1", "tank", "Резервуар", "Резервуар-усреднитель", "РВС-100", "TNK-0601", "TNK-001", "Резервуары-усреднители", 8120, 10000, "CHECK_RECOMMENDED", "100 м³; атмосферный; сталь 09Г2С"),
            arrayOf("CAB-1", "control_cabinet", "Шкаф управления", "Шкаф управления насосным агрегатом", "КШУ-0201", "CAB-0201", "CAB-001", "Машинный зал", 3920, 20000, "NORMAL", "380 В; IP54; ПЛК и частотный привод"),
            arrayOf("SNS-1", "sensor", "Датчик КИПиА", "Датчик температуры Pt100", "ЭЛЕМЕР ПТ 0304Ex", "SNS-0201", "SNS-001", "Очистные сооружения", 3920, 15000, "NORMAL", "-50…+180 °C; Ex; класс A")
        )
        rows.forEach { row ->
            db.execSQL("INSERT OR IGNORE INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
        }
    }

    fun login(email: String, password: String): LocalUser? {
        val normalizedEmail = email.trim().lowercase()
        var encodedPassword: String? = null
        var result: LocalUser? = null
        readableDatabase.rawQuery("SELECT id,email,name,role,position,password_hash FROM users WHERE lower(email)=?", arrayOf(normalizedEmail)).use { c ->
            if (!c.moveToFirst()) return null
            encodedPassword = c.getString(5)
            if (!verifyPassword(password, encodedPassword.orEmpty())) return null
            result = LocalUser(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
        val authenticated = result ?: return null
        if (encodedPassword?.startsWith("pbkdf2$") != true) {
            writableDatabase.execSQL("UPDATE users SET password_hash=? WHERE id=?", arrayOf(hashPassword(password), authenticated.id))
        }
        return authenticated
    }

    /** Restores a previously authenticated local identity without storing a password. */
    fun findUser(email: String): LocalUser? {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT id,email,name,role,position FROM users WHERE lower(email)=?",
            arrayOf(normalizedEmail)
        ).use { c ->
            if (c.moveToFirst()) LocalUser(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
        }
    }

    fun register(email: String, password: String, name: String, position: String = "Инженер по эксплуатации"): LocalUser? {
        return try {
            val id = writableDatabase.compileStatement("INSERT INTO users(email,password_hash,name,role,position,created_at) VALUES(?,?,?,?,?,?)").run {
                bindString(1, email.trim().lowercase()); bindString(2, hashPassword(password)); bindString(3, name.trim()); bindString(4, "engineer"); bindString(5, position.trim()); bindString(6, System.currentTimeMillis().toString()); executeInsert()
            }
            LocalUser(id, email.trim().lowercase(), name.trim(), "engineer", position.trim())
        } catch (_: Exception) { null }
    }

    /** Cache a server-authenticated identity so future field logins work offline. */
    fun cacheAuthenticatedUser(email: String, password: String, name: String, position: String = "Инженер по эксплуатации", role: String): LocalUser? {
        val normalizedEmail = email.trim().lowercase()
        val safeRole = role.takeIf { it in setOf("engineer", "admin") } ?: "engineer"
        if (normalizedEmail.isBlank() || name.isBlank()) return null
        return runCatching {
            writableDatabase.execSQL("UPDATE users SET password_hash=?,name=?,position=?,role=? WHERE lower(email)=?", arrayOf(hashPassword(password), name.trim(), position.trim(), safeRole, normalizedEmail))
            val exists = readableDatabase.rawQuery("SELECT id FROM users WHERE lower(email)=?", arrayOf(normalizedEmail)).use { it.moveToFirst() }
            if (!exists) {
                writableDatabase.execSQL("INSERT INTO users(email,password_hash,name,role,position,created_at) VALUES(?,?,?,?,?,?)", arrayOf(normalizedEmail, hashPassword(password), name.trim(), safeRole, position.trim(), System.currentTimeMillis().toString()))
            }
            login(normalizedEmail, password)
        }.getOrNull()
    }

    fun users(): List<LocalUser> = buildList {
            readableDatabase.rawQuery("SELECT id,email,name,role,position FROM users ORDER BY name", null).use { c ->
            while (c.moveToNext()) add(LocalUser(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
        }
    }

    fun updateRole(userId: Long, role: String): Boolean {
        if (role !in setOf("engineer", "admin")) return false
        return writableDatabase.compileStatement("UPDATE users SET role=? WHERE id=?").run { bindString(1, role); bindLong(2, userId); executeUpdateDelete() > 0 }
    }

    fun equipment(): List<EquipmentRecord> {
        val result = mutableListOf<EquipmentRecord>()
        readableDatabase.rawQuery("SELECT id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs,cover_path FROM equipment ORDER BY type,name", null).use { c ->
            while (c.moveToNext()) {
                val equipmentId = c.getString(0)
                result += EquipmentRecord(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getString(6), c.getString(7), c.getDouble(8), c.getDouble(9),
                    c.getString(10), c.getString(11), components(equipmentId), c.getString(12),
                    photosForEquipment(equipmentId), componentRecords(equipmentId)
                )
            }
        }
        return result
    }

    private fun components(equipmentId: String): List<String> = buildList {
        readableDatabase.rawQuery("SELECT name FROM components WHERE equipment_id=? ORDER BY rowid", arrayOf(equipmentId)).use { c -> while (c.moveToNext()) add(c.getString(0)) }
    }

    fun componentRecords(equipmentId: String): List<ComponentRecord> = buildList {
        readableDatabase.rawQuery("SELECT id,equipment_id,name,status,comment,is_custom FROM components WHERE equipment_id=? ORDER BY rowid", arrayOf(equipmentId)).use { c ->
            while (c.moveToNext()) add(ComponentRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0))
        }
    }

    fun updateComponent(componentId: String, status: String, comment: String = ""): Boolean {
        val allowed = setOf("UNKNOWN", "NORMAL", "CHECK_RECOMMENDED", "POSSIBLE_DEFECT", "CRITICAL")
        if (status !in allowed) return false
        val values = android.content.ContentValues().apply { put("status", status); put("comment", comment.trim()) }
        return writableDatabase.update("components", values, "id=?", arrayOf(componentId)) > 0
    }

    fun addComponent(equipmentId: String, name: String, status: String = "UNKNOWN", comment: String = ""): String? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val id = "$equipmentId-custom-${System.currentTimeMillis()}"
        writableDatabase.execSQL("INSERT INTO components(id,equipment_id,name,status,comment,is_custom) VALUES(?,?,?,?,?,1)", arrayOf(id, equipmentId, clean, status, comment.trim()))
        return id
    }

    fun renameComponent(componentId: String, name: String): Boolean {
        if (name.trim().isBlank()) return false
        return writableDatabase.update("components", android.content.ContentValues().apply { put("name", name.trim()) }, "id=? AND is_custom=1", arrayOf(componentId)) > 0
    }

    fun deleteComponent(componentId: String): Boolean = writableDatabase.delete("components", "id=? AND is_custom=1", arrayOf(componentId)) > 0

    fun photosForEquipment(equipmentId: String): List<EquipmentPhoto> = buildList {
        readableDatabase.rawQuery("SELECT id,equipment_id,path,kind,sort_order,is_primary,created_at FROM equipment_photos WHERE equipment_id=? ORDER BY sort_order,id", arrayOf(equipmentId)).use { c ->
            while (c.moveToNext()) add(EquipmentPhoto(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5) != 0, c.getString(6)))
        }
    }

    fun saveInspection(equipmentId: String, userId: Long?, category: String, confidence: Float?, summary: String, recommendation: String, image: Bitmap? = null, status: String = "OPEN", detectedEquipmentType: String? = null, detectedPumpModel: String? = null, detectedAssetId: String? = null, ocrText: String = ""): Long {
        val imagePath = image?.let {
            val file = File(imageDir, "inspection_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            file.absolutePath
        }
        val values = android.content.ContentValues().apply {
            put("equipment_id", equipmentId); put("user_id", userId); put("created_at", System.currentTimeMillis().toString()); put("category", category); put("confidence", confidence); put("summary", summary); put("recommendation", recommendation); put("image_path", imagePath); put("status", status.ifBlank { "OPEN" }); put("detected_equipment_type", detectedEquipmentType); put("detected_pump_model", detectedPumpModel); put("detected_asset_id", detectedAssetId); put("ocr_text", ocrText)
        }
        return writableDatabase.insertOrThrow("inspections", null, values)
    }

    /** Save a passport image and keep the gallery capped at five files. */
    fun addEquipmentPhoto(equipmentId: String, bitmap: Bitmap, kind: String = "passport", replacePhotoId: Long? = null): EquipmentPhoto? {
        val existing = photosForEquipment(equipmentId)
        if (replacePhotoId == null && existing.size >= 5) return null
        val file = File(coverDir, "photo_${equipmentId}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        val db = writableDatabase
        if (replacePhotoId != null) {
            val old = db.rawQuery("SELECT path FROM equipment_photos WHERE id=? AND equipment_id=?", arrayOf(replacePhotoId.toString(), equipmentId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val values = android.content.ContentValues().apply { put("path", file.absolutePath); put("kind", kind) }
            if (db.update("equipment_photos", values, "id=? AND equipment_id=?", arrayOf(replacePhotoId.toString(), equipmentId)) == 0) { file.delete(); return null }
            if (!old.isNullOrBlank()) runCatching { File(old).delete() }
        } else {
            val primary = if (existing.isEmpty()) 1 else 0
            val values = android.content.ContentValues().apply { put("equipment_id", equipmentId); put("path", file.absolutePath); put("kind", kind); put("sort_order", existing.size); put("is_primary", primary); put("created_at", System.currentTimeMillis().toString()) }
            db.insertOrThrow("equipment_photos", null, values)
        }
        // Keep the legacy cover path synchronized for older UI versions.
        val primary = photosForEquipment(equipmentId).firstOrNull { it.isPrimary } ?: photosForEquipment(equipmentId).firstOrNull()
        if (primary != null) db.update("equipment", android.content.ContentValues().apply { put("cover_path", primary.path) }, "id=?", arrayOf(equipmentId))
        return photosForEquipment(equipmentId).firstOrNull { it.path == file.absolutePath }
    }

    fun replaceEquipmentPhoto(equipmentId: String, photoId: Long, bitmap: Bitmap, kind: String = "passport"): EquipmentPhoto? =
        addEquipmentPhoto(equipmentId, bitmap, kind, replacePhotoId = photoId)

    fun deleteEquipmentPhoto(photoId: Long): Boolean {
        val db = writableDatabase
        val row = db.rawQuery("SELECT equipment_id,path,is_primary FROM equipment_photos WHERE id=?", arrayOf(photoId.toString())).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getInt(2) != 0) else null } ?: return false
        val deleted = db.delete("equipment_photos", "id=?", arrayOf(photoId.toString())) > 0
        if (!deleted) return false
        runCatching { File(row.second).delete() }
        val remaining = photosForEquipment(row.first)
        if (remaining.isNotEmpty() && row.third) setPrimaryPhoto(remaining.first().id)
        db.update("equipment", android.content.ContentValues().apply { put("cover_path", remaining.firstOrNull { it.isPrimary }?.path) }, "id=?", arrayOf(row.first))
        return true
    }

    fun setPrimaryPhoto(photoId: Long): Boolean {
        val db = writableDatabase
        val equipmentId = db.rawQuery("SELECT equipment_id FROM equipment_photos WHERE id=?", arrayOf(photoId.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return false
        db.execSQL("UPDATE equipment_photos SET is_primary=0 WHERE equipment_id=?", arrayOf(equipmentId))
        if (db.update("equipment_photos", android.content.ContentValues().apply { put("is_primary", 1) }, "id=? AND equipment_id=?", arrayOf(photoId.toString(), equipmentId)) == 0) return false
        val path = db.rawQuery("SELECT path FROM equipment_photos WHERE id=?", arrayOf(photoId.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        db.update("equipment", android.content.ContentValues().apply { put("cover_path", path) }, "id=?", arrayOf(equipmentId))
        return true
    }

    fun reorderEquipmentPhotos(equipmentId: String, orderedIds: List<Long>): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            orderedIds.forEachIndexed { index, id -> db.execSQL("UPDATE equipment_photos SET sort_order=? WHERE id=? AND equipment_id=?", arrayOf(index, id, equipmentId)) }
            db.setTransactionSuccessful(); true
        } finally { db.endTransaction() }
    }

    fun saveComponentInspection(inspectionId: Long?, componentId: String, equipmentId: String, status: String, comment: String = ""): Long {
        updateComponent(componentId, status, comment)
        val values = android.content.ContentValues().apply { put("inspection_id", inspectionId); put("component_id", componentId); put("equipment_id", equipmentId); put("status", status); put("comment", comment.trim()); put("created_at", System.currentTimeMillis().toString()) }
        return writableDatabase.insertOrThrow("component_inspections", null, values)
    }

    fun componentInspectionHistory(equipmentId: String): List<ComponentInspection> = buildList {
        readableDatabase.rawQuery("SELECT ci.id,ci.inspection_id,ci.component_id,ci.equipment_id,ci.status,ci.comment,ci.created_at,COALESCE(c.name,'') FROM component_inspections ci LEFT JOIN components c ON c.id=ci.component_id WHERE ci.equipment_id=? ORDER BY ci.id DESC", arrayOf(equipmentId)).use { c ->
            while (c.moveToNext()) add(ComponentInspection(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
        }
    }

    fun applyRecommendedStatus(equipmentId: String, status: String): Boolean {
        val allowed = setOf("NORMAL", "CHECK_RECOMMENDED", "SERVICE_SOON", "SERVICE_DUE", "CRITICAL")
        if (status !in allowed) return false
        return writableDatabase.update("equipment", android.content.ContentValues().apply { put("status", status) }, "id=?", arrayOf(equipmentId)) > 0
    }

    fun saveFeedback(inspectionId: Long?, equipmentId: String, userId: Long?, predictedCategory: String, correctedCategory: String, component: String?, comment: String = "") {
        writableDatabase.execSQL("INSERT INTO feedback_queue(inspection_id,equipment_id,user_id,predicted_category,corrected_category,component,comment,created_at,exported) VALUES(?,?,?,?,?,?,?,?,0)", arrayOf(inspectionId, equipmentId, userId, predictedCategory, correctedCategory, component, comment, System.currentTimeMillis().toString()))
    }

    fun feedbackCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM feedback_queue WHERE exported=0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun exportFeedback(): String? = runCatching {
        val dir = File(context.filesDir, "feedback_exports").apply { mkdirs() }
        val file = File(dir, "feedback_${System.currentTimeMillis()}.jsonl")
        FileOutputStream(file).use { out ->
            feedback().filter { !it.exported }.forEach { item ->
                val json = org.json.JSONObject().apply {
                    put("id", item.id)
                    put("inspectionId", item.inspectionId)
                    put("equipmentId", item.equipmentId)
                    put("predictedCategory", item.predictedCategory)
                    put("correctedCategory", item.correctedCategory)
                    put("component", item.component)
                    put("comment", item.comment)
                    put("createdAt", item.createdAt)
                }
                out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        }
        return file.absolutePath
    }.getOrNull()

    /** Export engineer decisions into the user-visible Downloads/KST Energo folder. */
    fun exportFeedbackToDownloads(): String? = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pending = feedback().filter { !it.exported }
        val payload = buildString {
            pending.forEach { item ->
                append(org.json.JSONObject().apply {
                    put("id", item.id); put("inspectionId", item.inspectionId); put("equipmentId", item.equipmentId)
                    put("predictedCategory", item.predictedCategory); put("correctedCategory", item.correctedCategory)
                    put("component", item.component); put("comment", item.comment); put("createdAt", item.createdAt)
                }.toString()).append('\n')
            }
        }
        val result = writePublicDownload("KST_feedback_$stamp.jsonl", "application/json", payload.toByteArray(Charsets.UTF_8))
        if (result != null && pending.isNotEmpty()) {
            writableDatabase.execSQL("UPDATE feedback_queue SET exported=1 WHERE id IN (${pending.joinToString(",") { "?" }})", pending.map { it.id }.toTypedArray())
        }
        result
    }.getOrNull()

    fun feedback(): List<FeedbackRecord> = buildList {
        readableDatabase.rawQuery("SELECT id,inspection_id,equipment_id,user_id,predicted_category,corrected_category,component,comment,created_at,exported FROM feedback_queue ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) add(FeedbackRecord(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getString(7), c.getString(8), c.getInt(9) != 0))
        }
    }

    fun inspections(equipmentId: String): List<InspectionRecord> = buildList {
            readableDatabase.rawQuery("SELECT i.id,i.equipment_id,COALESCE(e.name,''),i.category,i.summary,i.recommendation,i.created_at,i.image_path,i.confidence,i.user_id,i.status,i.detected_equipment_type,i.detected_pump_model,i.detected_asset_id,i.ocr_text FROM inspections i LEFT JOIN equipment e ON e.id=i.equipment_id WHERE i.equipment_id=? ORDER BY i.id DESC", arrayOf(equipmentId)).use { c ->
            while (c.moveToNext()) add(InspectionRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else c.getFloat(8), if (c.isNull(9)) null else c.getLong(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14)))
        }
    }

    fun allInspections(): List<InspectionRecord> = buildList {
            readableDatabase.rawQuery("SELECT i.id,i.equipment_id,COALESCE(e.name,''),i.category,i.summary,i.recommendation,i.created_at,i.image_path,i.confidence,i.user_id,i.status,i.detected_equipment_type,i.detected_pump_model,i.detected_asset_id,i.ocr_text FROM inspections i LEFT JOIN equipment e ON e.id=i.equipment_id ORDER BY i.id DESC", null).use { c ->
            while (c.moveToNext()) add(InspectionRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else c.getFloat(8), if (c.isNull(9)) null else c.getLong(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14)))
        }
    }

    fun inspectionDetails(inspectionId: Long): InspectionDetails? {
        val inspection = readableDatabase.rawQuery("SELECT i.id,i.equipment_id,COALESCE(e.name,''),i.category,i.summary,i.recommendation,i.created_at,i.image_path,i.confidence,i.user_id,i.status,i.detected_equipment_type,i.detected_pump_model,i.detected_asset_id,i.ocr_text FROM inspections i LEFT JOIN equipment e ON e.id=i.equipment_id WHERE i.id=?", arrayOf(inspectionId.toString())).use { c ->
            if (!c.moveToFirst()) null else InspectionRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else c.getFloat(8), if (c.isNull(9)) null else c.getLong(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14))
        } ?: return null
        return InspectionDetails(inspection, componentInspectionHistory(inspection.equipmentId).filter { it.inspectionId == inspectionId })
    }

    /** Remove an incorrect inspection from the local journal, including its captured image and labels. */
    fun deleteInspection(inspectionId: Long): Boolean {
        val db = writableDatabase
        val imagePath = db.rawQuery("SELECT image_path FROM inspections WHERE id=?", arrayOf(inspectionId.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        db.delete("component_inspections", "inspection_id=?", arrayOf(inspectionId.toString()))
        db.delete("feedback_queue", "inspection_id=?", arrayOf(inspectionId.toString()))
        val deleted = db.delete("inspections", "id=?", arrayOf(inspectionId.toString())) > 0
        if (deleted && !imagePath.isNullOrBlank()) runCatching { File(imagePath).delete() }
        return deleted
    }

    fun maintenance(): List<MaintenanceRecord> = buildList {
        readableDatabase.rawQuery("SELECT e.id,e.name,CASE WHEN e.status='SERVICE_SOON' THEN 'ТО в ближайшие 120 ч' WHEN e.status='CHECK_RECOMMENDED' THEN 'Требуется проверка' WHEN e.status='CRITICAL' THEN 'Критический статус' ELSE 'Регламентная эксплуатация' END,e.status FROM equipment e ORDER BY CASE e.status WHEN 'CRITICAL' THEN 0 WHEN 'SERVICE_SOON' THEN 1 WHEN 'CHECK_RECOMMENDED' THEN 2 ELSE 3 END,e.name", null).use { c ->
            while (c.moveToNext()) add(MaintenanceRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
        }
    }

    fun addCustomEquipment(name: String, typeLabel: String, tag: String, location: String, specs: String, cover: Bitmap? = null): String {
        val id = "CUSTOM-" + System.currentTimeMillis()
        val coverPath = cover?.let { bitmap ->
            val file = File(coverDir, "cover_$id.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            file.absolutePath
        }
        val normalizedType = when {
            typeLabel.contains("насос", true) -> "pump"
            typeLabel.contains("двигател", true) -> "motor"
            typeLabel.contains("редуктор", true) -> "gearbox"
            typeLabel.contains("вентилятор", true) -> "fan"
            typeLabel.contains("клапан", true) || typeLabel.contains("задвиж", true) -> "valve"
            typeLabel.contains("резервуар", true) -> "tank"
            typeLabel.contains("шкаф", true) -> "control_cabinet"
            typeLabel.contains("датчик", true) -> "sensor"
            typeLabel.contains("труб", true) -> "pipe"
            else -> "pipe"
        }
        val safeName = name.trim().ifBlank { "Новый объект — $typeLabel" }
        val safeTag = tag.trim().ifBlank { "LOCAL-${System.currentTimeMillis()}" }
        val safeLocation = location.trim().ifBlank { "Не указано" }
        val safeSpecs = specs.trim().ifBlank { "Параметры не заполнены" }
        writableDatabase.execSQL("INSERT INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs,cover_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, normalizedType, typeLabel, safeName, typeLabel, safeTag, id, safeLocation, 0, 10000, "CHECK_RECOMMENDED", safeSpecs, coverPath))
        if (!coverPath.isNullOrBlank()) {
            writableDatabase.execSQL("INSERT INTO equipment_photos(equipment_id,path,kind,sort_order,is_primary,created_at) VALUES(?,?,?,0,1,?)", arrayOf(id, coverPath, "passport", System.currentTimeMillis().toString()))
        }
        ensureComponentTemplates(writableDatabase, id)
        return id
    }

    fun updateEquipment(id: String, name: String, tag: String, location: String, specs: String, status: String): Boolean {
        val values = android.content.ContentValues().apply {
            put("name", name.trim())
            put("tag", tag.trim())
            put("location", location.trim())
            put("specs", specs.trim())
            put("status", status.trim().ifBlank { "NORMAL" })
        }
        return writableDatabase.update("equipment", values, "id=?", arrayOf(id)) > 0
    }

    fun deleteEquipment(id: String): Boolean {
        val db = writableDatabase
        val cover = db.rawQuery("SELECT cover_path FROM equipment WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        val galleryPaths = buildList {
            db.rawQuery("SELECT path FROM equipment_photos WHERE equipment_id=?", arrayOf(id)).use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
        db.delete("components", "equipment_id=?", arrayOf(id))
        db.delete("component_inspections", "equipment_id=?", arrayOf(id))
        db.delete("equipment_photos", "equipment_id=?", arrayOf(id))
        db.delete("inspections", "equipment_id=?", arrayOf(id))
        db.delete("maintenance_events", "equipment_id=?", arrayOf(id))
        db.delete("feedback_queue", "equipment_id=?", arrayOf(id))
        val deleted = db.delete("equipment", "id=?", arrayOf(id)) > 0
        if (deleted && !cover.isNullOrBlank()) runCatching { File(cover).delete() }
        galleryPaths.forEach { path -> if (path != cover) runCatching { File(path).delete() } }
        return deleted
    }

    fun backupDatabase(): String? {
        return runCatching {
            close()
            val source = context.getDatabasePath("kst_offline.db")
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(dir, "kst_offline_$stamp.db")
            FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            target.absolutePath
        }.getOrNull()
    }

    /** Copy the SQLite database to a user-visible Downloads/KST Energo folder. */
    fun backupDatabaseToDownloads(): String? = runCatching {
        val source = context.getDatabasePath("kst_offline.db")
        if (!source.exists()) return@runCatching null
        val bytes = FileInputStream(source).use { it.readBytes() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        writePublicDownload("KST_database_$stamp.db", "application/octet-stream", bytes)
    }.getOrNull()

    private fun writePublicDownload(fileName: String, mimeType: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KST Energo")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                "Загрузки/KST Energo/$fileName"
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null); throw error
            }
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KST Energo").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    fun backupDirectoryPath(): String = File(context.filesDir, "backups").apply { mkdirs() }.absolutePath

    fun feedbackExportDirectoryPath(): String = File(context.filesDir, "feedback_exports").apply { mkdirs() }.absolutePath

    companion object {
        private const val PBKDF2_ITERATIONS = 120_000

        fun hashPassword(value: String): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val spec = PBEKeySpec(value.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + Base64.encodeToString(salt, Base64.NO_WRAP) + "$" + Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        fun hash(value: String): String = hashPassword(value)

        private fun verifyPassword(value: String, encoded: String): Boolean {
            if (!encoded.startsWith("pbkdf2$")) {
                val legacy = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                return MessageDigest.isEqual(legacy.toByteArray(), encoded.toByteArray())
            }
            return runCatching {
                val parts = encoded.split("$")
                val iterations = parts[1].toInt().coerceIn(10_000, 1_000_000)
                val salt = Base64.decode(parts[2], Base64.NO_WRAP)
                val expected = Base64.decode(parts[3], Base64.NO_WRAP)
                val spec = PBEKeySpec(value.toCharArray(), salt, iterations, expected.size * 8)
                val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                spec.clearPassword()
                MessageDigest.isEqual(actual, expected)
            }.getOrDefault(false)
        }
    }
}
