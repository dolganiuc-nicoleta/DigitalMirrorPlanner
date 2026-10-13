package md.utm.digitalmirror.ui.goals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.data.entity.GoalCategory
import md.utm.digitalmirror.data.entity.GoalStep
import md.utm.digitalmirror.domain.GoalProgress
import java.time.LocalDate

class GoalsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.goalDao()

    /** Un obiectiv cu tot ce trebuie ecranului: categoriile, etapele și progresul calculat. */
    data class GoalCard(
        val goal: Goal,
        val categoryIds: Set<Long>,
        val categoryNames: List<String>,
        val steps: List<GoalStep>,
        val result: GoalProgress.Result
    )

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<GoalCard>> =
        combine(
            dao.observeGoals(),
            dao.observeLinks(),
            dao.observeSteps(),
            dao.observeClosed(),
            db.categoryDao().observeAll()
        ) { goals, links, steps, closed, categories ->
            val today = LocalDate.now()
            goals.map { goal ->
                val categoryIds = links.filter { it.goalId == goal.id }.map { it.categoryId }.toSet()
                val contributions = closed
                    .filter { it.categoryId in categoryIds }
                    .map { GoalProgress.Contribution(LocalDate.parse(it.date), it.minutes) }

                GoalCard(
                    goal = goal,
                    categoryIds = categoryIds,
                    categoryNames = categories.filter { it.id in categoryIds }.map { it.name },
                    steps = steps.filter { it.goalId == goal.id },
                    result = GoalProgress.compute(
                        GoalProgress.Goal(
                            startDate = LocalDate.parse(goal.startDate),
                            deadline = goal.deadline?.let { LocalDate.parse(it) },
                            targetMinutes = goal.targetMinutes,
                            targetActivities = goal.targetActivities
                        ),
                        contributions,
                        today
                    )
                )
            }
                // întâi cele active, apoi cele atinse; între ele, după termen (cele fără termen la final)
                .sortedWith(
                    compareBy<GoalCard> { it.goal.state != Goal.STATE_ACTIVE }
                        .thenBy { it.goal.deadline ?: "9999-12-31" }
                )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Obiectiv nou (id = 0) sau modificat. */
    fun save(goal: Goal, categoryIds: Set<Long>) = viewModelScope.launch {
        val id = if (goal.id == 0L) dao.insertGoal(goal) else goal.id.also { dao.updateGoal(goal) }
        dao.deleteLinks(id)
        dao.insertLinks(categoryIds.map { GoalCategory(id, it) })
    }

    fun delete(goal: Goal) = viewModelScope.launch {
        dao.deleteLinks(goal.id)
        dao.deleteSteps(goal.id)
        dao.deleteGoal(goal)
    }

    fun setState(goal: Goal, state: String) = viewModelScope.launch {
        dao.updateGoal(goal.copy(state = state))
    }

    fun addStep(goalId: Long, title: String, position: Int) = viewModelScope.launch {
        if (title.isNotBlank()) dao.insertStep(GoalStep(goalId = goalId, title = title.trim(), done = false, position = position))
    }

    fun toggleStep(step: GoalStep) = viewModelScope.launch {
        dao.updateStep(step.copy(done = !step.done))
    }

    fun deleteStep(step: GoalStep) = viewModelScope.launch { dao.deleteStep(step) }
}