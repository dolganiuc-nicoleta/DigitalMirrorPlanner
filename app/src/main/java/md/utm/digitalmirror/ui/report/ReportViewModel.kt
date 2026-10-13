package md.utm.digitalmirror.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.domain.PeriodReport
import java.time.LocalDate

/** Tot ce arată ecranul de raport pentru săptămâna aleasă. */
data class ReportState(
    val weekStart: LocalDate,
    val isCurrentWeek: Boolean,
    val week: PeriodReport.Summary,
    val previousWeek: PeriodReport.Summary,
    val observations: List<String>,
    val series: List<PeriodReport.Summary>,        // 8 săptămâni, până la cea aleasă
    val compare7: PeriodReport.Comparison,
    val compare30: PeriodReport.Comparison,
    val repeated: List<PeriodReport.Postponed>,
    val topCategory: String?,
    val hasAnyData: Boolean
)

class ReportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: PlannerRepository = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }

    // 0 = săptămâna curentă, -1 = cea dinainte etc.
    private val offset = MutableStateFlow(0)

    val state: StateFlow<ReportState?> =
        combine(
            offset,
            repo.observeActivitiesInRange("0000-01-01", "9999-12-31"),
            repo.observeCategories()
        ) { weekOffset, activities, categories ->
            val today = LocalDate.now()
            val items = activities.map {
                PeriodReport.Item(
                    date = LocalDate.parse(it.date),
                    state = it.state,
                    actualMinutes = it.actualMinutes,
                    categoryId = it.categoryId,
                    title = it.title,
                    titleKey = it.titleKey,
                    postponedCount = it.postponedCount
                )
            }

            val start = PeriodReport.weekStart(today).plusWeeks(weekOffset.toLong())
            val week = PeriodReport.summarize(items, start, start.plusDays(6), today)
            val previous = PeriodReport.summarize(items, start.minusWeeks(1), start.minusDays(1), today)

            val names = categories.associate { it.id to it.name }
            val top = week.minutesByCategory.maxByOrNull { it.value }?.takeIf { it.value > 0 }
            val topName = top?.let { entry -> entry.key?.let { id -> names[id] } ?: "Fără categorie" }
            val repeated = PeriodReport.repeatedlyPostponed(items)

            ReportState(
                weekStart = start,
                isCurrentWeek = weekOffset == 0,
                week = week,
                previousWeek = previous,
                observations = PeriodReport.observations(week, previous, "săptămâna anterioară", topName, repeated),
                series = PeriodReport.weeks(items, start, 8, today),
                compare7 = PeriodReport.compare(items, 7, today),
                compare30 = PeriodReport.compare(items, 30, today),
                repeated = repeated,
                topCategory = topName,
                hasAnyData = items.isNotEmpty()
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun previousWeek() { offset.value -= 1 }

    fun nextWeek() { if (offset.value < 0) offset.value += 1 }

    fun currentWeek() { offset.value = 0 }
}