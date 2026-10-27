package md.utm.digitalmirror.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.db.AppDatabase
import android.net.Uri
import md.utm.digitalmirror.data.backup.BackupManager
import md.utm.digitalmirror.data.backup.CloudBackup
import md.utm.digitalmirror.data.backup.CloudConfig
import md.utm.digitalmirror.data.entity.AvailabilityRule
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.DayOff
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.travel.TravelRepository
import md.utm.digitalmirror.data.export.DataExporter
import md.utm.digitalmirror.data.health.HealthSync
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.reminder.ReminderPrefs
import md.utm.digitalmirror.reminder.ReminderScheduler

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

    // 1. reminder
    private val _reminderEnabled = MutableStateFlow(ReminderPrefs.isEnabled(app))
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled

    private val _reminderMinute = MutableStateFlow(ReminderPrefs.minuteOfDay(app))
    val reminderMinute: StateFlow<Int> = _reminderMinute

    fun setReminderEnabled(enabled: Boolean) {
        _reminderEnabled.value = enabled
        saveReminder()
    }

    fun setReminderMinute(minute: Int) {
        _reminderMinute.value = minute
        saveReminder()
    }

    private fun saveReminder() {
        ReminderPrefs.save(app, _reminderEnabled.value, _reminderMinute.value)
        ReminderScheduler.schedule(app, replaceExisting = true)
    }

    // locurile alese pe hartă (doar numărul; lista e pe ecranul Locuri)
    val placeCount: StateFlow<Int> =
        db.placeDao().observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 2. categorii
    val categories: StateFlow<List<Category>> =
        repo.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addCategory(name.trim())
    }

    fun renameCategory(category: Category, newName: String) = viewModelScope.launch {
        if (newName.isNotBlank()) repo.renameCategory(category, newName.trim())
    }

    fun deleteCategory(category: Category) = viewModelScope.launch {
        repo.deleteCategory(category)
    }

    // 3. Health Connect
    private val health = HealthSync(app)

    private val _healthStatus = MutableStateFlow(HealthSync.Status.NOT_CONNECTED)
    val healthStatus: StateFlow<HealthSync.Status> = _healthStatus

    private val _healthMessage = MutableStateFlow<String?>(null)
    val healthMessage: StateFlow<String?> = _healthMessage

    init {
        refreshHealthStatus()
    }

    fun refreshHealthStatus() = viewModelScope.launch {
        _healthStatus.value = health.status()
    }

    // apelat după ce utilizatorul a răspuns la ecranul de permisiuni
    fun onHealthPermissionsResult() = viewModelScope.launch {
        _healthStatus.value = health.status()
        if (_healthStatus.value == HealthSync.Status.CONNECTED) syncHealth()
        else _healthMessage.value = "Permisiunile nu au fost acordate"
    }

    fun syncHealth() = viewModelScope.launch {
        _healthMessage.value = "Se sincronizează…"
        val days = health.syncIfPermitted()
        _healthMessage.value = when {
            days == null -> "Sincronizarea nu a reușit"
            days == 0 -> "Nu s-au găsit date de somn sau pași"
            days == 1 -> "Sincronizat · 1 zi cu date"
            else -> "Sincronizat · $days zile cu date"
        }
    }

    // 4. export
    val participantId: String = DataExporter.participantId(app)

    fun exportFileName(full: Boolean) = DataExporter.suggestedFileName(app, full)

    fun export(uri: Uri, full: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        val message = try {
            val count = DataExporter.export(app, uri, full)
            "Export gata · $count activități"
        } catch (e: Exception) {
            "Exportul nu a reușit: ${e.message}"
        }
        onDone(message)
    }

    fun csvFileName(full: Boolean) = DataExporter.suggestedCsvName(app, full)

    fun exportCsv(uri: Uri, full: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        val message = try {
            val count = DataExporter.exportCsv(app, uri, full)
            "Export CSV gata · $count activități"
        } catch (e: Exception) {
            "Exportul nu a reușit: ${e.message}"
        }
        onDone(message)
    }

    // 4a. analiza și recomandările
    private val _analysisEnabled = MutableStateFlow(AnalysisPrefs.isEnabled(app))
    val analysisEnabled: StateFlow<Boolean> = _analysisEnabled

    fun setAnalysisEnabled(enabled: Boolean) {
        AnalysisPrefs.setEnabled(app, enabled)
        _analysisEnabled.value = enabled
    }

    // 4b. blocuri fixe (cursuri, masă, somn) și zile libere
    private val availabilityDao = db.availabilityDao()

    val rules: StateFlow<List<AvailabilityRule>> =
        availabilityDao.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val daysOff: StateFlow<List<String>> =
        availabilityDao.observeDaysOff()
            .map { list -> list.map { it.date } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Adaugă un bloc nou (id = 0) sau îl actualizează pe cel existent. */
    fun saveRule(rule: AvailabilityRule) = viewModelScope.launch {
        if (rule.id == 0L) availabilityDao.insertRule(rule) else availabilityDao.updateRule(rule)
    }

    // locurile și drumul până la blocurile fixe
    val places: StateFlow<List<Place>> =
        db.placeDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val travel = TravelRepository(app)

    fun homePlaceId(): Long? = TravelPrefs.homePlaceId(app)

    /**
     * Drumul de la locul de plecare până la bloc și înapoi, în minute (înainte, după),
     * cu modul de deplasare ales în Locuri. Se reține locul de plecare pentru data viitoare.
     */
    suspend fun estimateBlockTravel(fromId: Long, blockPlaceId: Long): Result<Pair<Int, Int>> {
        val all = places.value
        val from = all.find { it.id == fromId } ?: return Result.failure(IllegalStateException("Alege de unde pleci."))
        val to = all.find { it.id == blockPlaceId } ?: return Result.failure(IllegalStateException("Alege locul blocului."))
        TravelPrefs.setHomePlaceId(app, from.id)
        val mode = TravelPrefs.mode(app)
        val there = travel.estimate(from, to, mode).getOrElse { return Result.failure(it) }
        val back = travel.estimate(to, from, mode).getOrElse { return Result.failure(it) }
        return Result.success(there.estimate.minutes to back.estimate.minutes)
    }

    fun travelModeLabel(): String = TravelPrefs.mode(app).label

    fun deleteRule(rule: AvailabilityRule) = viewModelScope.launch { availabilityDao.deleteRule(rule) }

    fun addDayOff(date: String) = viewModelScope.launch { availabilityDao.insertDayOff(DayOff(date)) }

    fun removeDayOff(date: String) = viewModelScope.launch { availabilityDao.deleteDayOff(date) }

    // 5. copie de rezervă și restaurare
    private val _restorePreview = MutableStateFlow<BackupManager.Preview?>(null)
    val restorePreview: StateFlow<BackupManager.Preview?> = _restorePreview

    fun backupFileName() = BackupManager.suggestedFileName()

    fun createBackup(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val message = try {
            val rows = BackupManager.create(app, uri)
            "Copie salvată · $rows înregistrări"
        } catch (e: Exception) {
            "Copia nu a reușit: ${e.message}"
        }
        onDone(message)
    }

    /** Citește fișierul ales și îl arată utilizatorului; abia după confirmare se restaurează. */
    fun readBackup(uri: Uri, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            _restorePreview.value = BackupManager.read(app, uri)
        } catch (e: Exception) {
            onError(e.message ?: "Fișierul nu a putut fi citit.")
        }
    }

    fun cancelRestore() {
        _restorePreview.value = null
    }

    fun confirmRestore(onDone: (String) -> Unit) = viewModelScope.launch {
        val preview = _restorePreview.value ?: return@launch
        _restorePreview.value = null
        val message = try {
            BackupManager.restore(app, preview)
            // setările de reminder s-au schimbat și ele
            _reminderEnabled.value = ReminderPrefs.isEnabled(app)
            _reminderMinute.value = ReminderPrefs.minuteOfDay(app)
            "Datele au fost restaurate"
        } catch (e: Exception) {
            "Restaurarea nu a reușit, nu s-a schimbat nimic: ${e.message}"
        }
        onDone(message)
    }

    // 5b. copie de rezervă în cloud
    private val _cloudStatus = MutableStateFlow("")
    val cloudStatus: StateFlow<String> = _cloudStatus
    private val _cloudBusy = MutableStateFlow(false)
    val cloudBusy: StateFlow<Boolean> = _cloudBusy

    fun refreshCloud() = viewModelScope.launch {
        if (!CloudConfig.isConfigured) return@launch
        _cloudStatus.value = try {
            val info = CloudBackup.info()
            if (info.exists) "Ultima copie în cloud: ${info.savedAtLabel()}" else "Încă nu ai nicio copie în cloud."
        } catch (e: Exception) {
            e.message ?: "Nu am putut verifica."
        }
    }

    fun uploadCloud(onDone: (String) -> Unit) = viewModelScope.launch {
        _cloudBusy.value = true
        val message = try {
            val rows = CloudBackup.upload(app)
            "Copie salvată în cloud · $rows înregistrări"
        } catch (e: Exception) {
            "Copia în cloud nu a reușit: ${e.message}"
        }
        _cloudBusy.value = false
        refreshCloud()
        onDone(message)
    }

    /** Descarcă copia din cloud și o arată în același dialog ca restaurarea din fișier. */
    fun readCloud(onError: (String) -> Unit) = viewModelScope.launch {
        _cloudBusy.value = true
        try {
            _restorePreview.value = CloudBackup.download(app)
        } catch (e: Exception) {
            onError(e.message ?: "Nu am putut descărca copia.")
        }
        _cloudBusy.value = false
    }

    fun deleteCloud(onDone: (String) -> Unit) = viewModelScope.launch {
        _cloudBusy.value = true
        val message = try {
            CloudBackup.delete()
            "Copia din cloud a fost ștearsă"
        } catch (e: Exception) {
            "Nu am putut șterge: ${e.message}"
        }
        _cloudBusy.value = false
        refreshCloud()
        onDone(message)
    }

    // 6. ștergerea datelor
    fun deleteAllData(onDone: () -> Unit) = viewModelScope.launch {
        // clearAllTables nu are voie să ruleze pe firul principal
        withContext(Dispatchers.IO) { db.clearAllTables() }
        onDone()
    }
}