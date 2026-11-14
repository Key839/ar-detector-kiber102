package com.example.arsystem

import android.content.Context
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
    val coverPath: String? = null
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
    val imagePath: String? = null
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

class OfflineStore(private val context: Context) : SQLiteOpenHelper(context, "kst_offline.db", null, 11) {
    private val imageDir = File(context.filesDir, "inspection_images").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "equipment_covers").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, position TEXT NOT NULL DEFAULT 'Инженер по эксплуатации', created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE equipment(id TEXT PRIMARY KEY, type TEXT NOT NULL, type_label TEXT NOT NULL, name TEXT NOT NULL, model TEXT NOT NULL, tag TEXT NOT NULL, serial TEXT NOT NULL, location TEXT NOT NULL, hours REAL NOT NULL, to_limit REAL NOT NULL, status TEXT NOT NULL, specs TEXT NOT NULL, cover_path TEXT)")
        db.execSQL("CREATE TABLE components(id TEXT PRIMARY KEY, equipment_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE inspections(id INTEGER PRIMARY KEY AUTOINCREMENT, equipment_id TEXT NOT NULL, user_id INTEGER, created_at TEXT NOT NULL, category TEXT NOT NULL, confidence REAL, summary TEXT NOT NULL, recommendation TEXT NOT NULL, image_path TEXT)")
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
            db.delete("equipment", "type NOT IN (?,?,?,?)", arrayOf("pump", "motor", "valve", "pipe"))
        }
    }

    private fun seed(db: SQLiteDatabase) {
        seedUsers(db)
        seedEquipment(db)
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
        readableDatabase.rawQuery("SELECT id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs,cover_path FROM equipment WHERE type IN ('pump','motor','valve','pipe') ORDER BY type,name", null).use { c ->
            while (c.moveToNext()) {
                result += EquipmentRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getDouble(8), c.getDouble(9), c.getString(10), c.getString(11), components(c.getString(0)), c.getString(12))
            }
        }
        return result
    }

    private fun components(equipmentId: String): List<String> = buildList {
        readableDatabase.rawQuery("SELECT name FROM components WHERE equipment_id=?", arrayOf(equipmentId)).use { c -> while (c.moveToNext()) add(c.getString(0)) }
    }

    fun saveInspection(equipmentId: String, userId: Long?, category: String, confidence: Float?, summary: String, recommendation: String, image: Bitmap? = null): Long {
        val imagePath = image?.let {
            val file = File(imageDir, "inspection_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            file.absolutePath
        }
        val values = android.content.ContentValues().apply {
            put("equipment_id", equipmentId); put("user_id", userId); put("created_at", System.currentTimeMillis().toString()); put("category", category); put("confidence", confidence); put("summary", summary); put("recommendation", recommendation); put("image_path", imagePath)
        }
        return writableDatabase.insertOrThrow("inspections", null, values)
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
        file.absolutePath
    }.getOrNull()

    fun feedback(): List<FeedbackRecord> = buildList {
        readableDatabase.rawQuery("SELECT id,inspection_id,equipment_id,user_id,predicted_category,corrected_category,component,comment,created_at,exported FROM feedback_queue ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) add(FeedbackRecord(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getString(7), c.getString(8), c.getInt(9) != 0))
        }
    }

    fun inspections(equipmentId: String): List<InspectionRecord> = buildList {
            readableDatabase.rawQuery("SELECT i.id,i.equipment_id,COALESCE(e.name,''),i.category,i.summary,i.recommendation,i.created_at,i.image_path FROM inspections i LEFT JOIN equipment e ON e.id=i.equipment_id WHERE i.equipment_id=? ORDER BY i.id DESC", arrayOf(equipmentId)).use { c ->
            while (c.moveToNext()) add(InspectionRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7)))
        }
    }

    fun allInspections(): List<InspectionRecord> = buildList {
            readableDatabase.rawQuery("SELECT i.id,i.equipment_id,COALESCE(e.name,''),i.category,i.summary,i.recommendation,i.created_at,i.image_path FROM inspections i LEFT JOIN equipment e ON e.id=i.equipment_id ORDER BY i.id DESC", null).use { c ->
            while (c.moveToNext()) add(InspectionRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7)))
        }
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
            typeLabel.contains("клапан", true) || typeLabel.contains("задвиж", true) -> "valve"
            else -> "pipe"
        }
        writableDatabase.execSQL("INSERT INTO equipment(id,type,type_label,name,model,tag,serial,location,hours,to_limit,status,specs,cover_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, normalizedType, typeLabel, name, typeLabel, tag, id, location, 0, 10000, "NORMAL", specs, coverPath))
        writableDatabase.execSQL("INSERT INTO components(id,equipment_id,name,status) VALUES(?,?,?,?)", arrayOf("$id-main", id, "Основной узел", "UNKNOWN"))
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
        db.delete("components", "equipment_id=?", arrayOf(id))
        db.delete("inspections", "equipment_id=?", arrayOf(id))
        db.delete("maintenance_events", "equipment_id=?", arrayOf(id))
        db.delete("feedback_queue", "equipment_id=?", arrayOf(id))
        val deleted = db.delete("equipment", "id=?", arrayOf(id)) > 0
        if (deleted && !cover.isNullOrBlank()) runCatching { File(cover).delete() }
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
