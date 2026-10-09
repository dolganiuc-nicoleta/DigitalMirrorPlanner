package md.utm.digitalmirror.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.repository.PlannerRepository

class OnboardingViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repo = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }

    // addCategory nu creează duplicate, deci categoriile existente rămân neatinse
    fun addCategories(names: Set<String>) = viewModelScope.launch {
        names.filter { it.isNotBlank() }.forEach { repo.addCategory(it.trim()) }
    }

    fun finish(pattern: OnboardingPrefs.Pattern) {
        OnboardingPrefs.save(app, pattern)
    }
}