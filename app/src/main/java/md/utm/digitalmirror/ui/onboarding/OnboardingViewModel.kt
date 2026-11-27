package md.utm.digitalmirror.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.prefs.SleepPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.TravelEstimator

class OnboardingViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

    /** Locurile adăugate (din ecranul Locuri, deschis din configurare). */
    val places: StateFlow<List<Place>> =
        db.placeDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Mesele cu ferestrele lor (cele implicite, sau cele deja alese). */
    val initialMeals: List<MealPlanner.Window> = MealPrefs.windows(app)
    val initialPrep: Int = TravelPrefs.prepMinutes(app)
    val initialMode: TravelEstimator.Mode = TravelPrefs.mode(app)
    val initialWake: Int = SleepPrefs.wake(app) ?: 7 * 60 + 30
    val initialBed: Int = SleepPrefs.bed(app) ?: 23 * 60

    // addCategory nu creează duplicate, deci categoriile existente rămân neatinse
    fun addCategories(names: Set<String>) = viewModelScope.launch {
        names.filter { it.isNotBlank() }.forEach { repo.addCategory(it.trim()) }
    }

    fun saveRhythm(wake: Int, bed: Int, prepMinutes: Int) {
        SleepPrefs.save(app, wake, bed)
        TravelPrefs.setPrepMinutes(app, prepMinutes)
    }

    fun saveMeals(enabled: Set<MealPlanner.Kind>) {
        MealPrefs.save(app, initialMeals.map { it.copy(enabled = it.kind in enabled) })
    }

    fun saveMode(mode: TravelEstimator.Mode) = TravelPrefs.setMode(app, mode)

    fun finish(pattern: OnboardingPrefs.Pattern) {
        OnboardingPrefs.save(app, pattern)
    }
}
