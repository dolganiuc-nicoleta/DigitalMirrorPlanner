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
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.export.DataExporter
import md.utm.digitalmirror.data.health.HealthSync
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

    // 5. ștergerea datelor
    fun deleteAllData(onDone: () -> Unit) = viewModelScope.launch {
        // clearAllTables nu are voie să ruleze pe firul principal
        withContext(Dispatchers.IO) { db.clearAllTables() }
        onDone()
    }
}