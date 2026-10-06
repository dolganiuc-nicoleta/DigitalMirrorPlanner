package md.utm.digitalmirror.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.repository.PlannerRepository
import java.time.LocalDate
import kotlin.math.roundToInt

// perioada pentru care se calculează statisticile
enum class StatsPeriod(val label: String, val days: Long?) {
    WEEK("7 zile", 7),
    MONTH("30 zile", 30),
    ALL("Tot", null)
}

// estimat vs. real pentru o categorie
data class CategoryStat(
    val name: String,
    val count: Int,
    val estimatedMinutes: Int,
    val actualMinutes: Int
) {
    // cu cât la sută durează mai mult (+) sau mai puțin (−) decât estimarea
    val deviationPercent: Int
        get() = if (estimatedMinutes == 0) 0
        else ((actualMinutes - estimatedMinutes) * 100f / estimatedMinutes).roundToInt()

    val averageActualMinutes: Int
        get() = if (count == 0) 0 else (actualMinutes.toFloat() / count).roundToInt()
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val total: CategoryStat? = null,               // null = nu există date
    val categories: List<CategoryStat> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: PlannerRepository = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }

    private val _period = MutableStateFlow(StatsPeriod.MONTH)

    val state: StateFlow<StatsUiState> =
        _period.flatMapLatest { period ->
            val today = LocalDate.now()
            val from = period.days?.let { today.minusDays(it - 1).toString() } ?: "0000-01-01"
            combine(
                repo.observeActivitiesInRange(from, today.toString()),
                repo.observeCategories()
            ) { activities, categories -> buildState(period, activities, categories) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }

    private fun buildState(
        period: StatsPeriod,
        activities: List<Activity>,
        categories: List<Category>
    ): StatsUiState {
        // luăm doar activitățile finalizate complet, care au și estimare, și durată reală.
        // cele parțiale sau abandonate ar strica comparația.
        val usable = activities.filter {
            it.state == "DONE" &&
                    (it.userEstimatedMinutes ?: 0) > 0 &&
                    it.actualMinutes != null
        }
        if (usable.isEmpty()) return StatsUiState(period = period)

        val names = categories.associate { it.id to it.name }

        val perCategory = usable
            .groupBy { it.categoryId }
            .map { (categoryId, list) ->
                CategoryStat(
                    name = categoryId?.let { names[it] } ?: "Fără categorie",
                    count = list.size,
                    estimatedMinutes = list.sumOf { it.userEstimatedMinutes ?: 0 },
                    actualMinutes = list.sumOf { it.actualMinutes ?: 0 }
                )
            }
            .sortedByDescending { it.actualMinutes }   // categoriile cu cel mai mult timp primele

        val total = CategoryStat(
            name = "Total",
            count = usable.size,
            estimatedMinutes = perCategory.sumOf { it.estimatedMinutes },
            actualMinutes = perCategory.sumOf { it.actualMinutes }
        )

        return StatsUiState(period = period, total = total, categories = perCategory)
    }
}