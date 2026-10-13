package md.utm.digitalmirror.data.capsule

import md.utm.digitalmirror.data.dao.ClosedActivity
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.data.entity.GoalCategory
import md.utm.digitalmirror.domain.GoalProgress
import java.time.LocalDate

/**
 * Calculează progresul obiectivelor la o anumită dată, din activitățile închise.
 * E folosit și de ecranul capsulei, și de verificarea zilnică din fundal.
 */
object GoalEvaluator {

    /** Progresul fiecărui obiectiv (după id) la data dată; datele ulterioare ei nu se numără. */
    fun evaluate(
        goals: List<Goal>,
        links: List<GoalCategory>,
        closed: List<ClosedActivity>,
        on: LocalDate
    ): Map<Long, GoalProgress.Result> = goals.associate { goal ->
        val categoryIds = links.filter { it.goalId == goal.id }.map { it.categoryId }.toSet()
        val contributions = closed
            .filter { it.categoryId in categoryIds }
            .map { GoalProgress.Contribution(LocalDate.parse(it.date), it.minutes) }

        goal.id to GoalProgress.compute(
            GoalProgress.Goal(
                startDate = LocalDate.parse(goal.startDate),
                deadline = goal.deadline?.let { LocalDate.parse(it) },
                targetMinutes = goal.targetMinutes,
                targetActivities = goal.targetActivities
            ),
            contributions,
            on
        )
    }

    /** Ritmul obiectivelor active dintre cele date; cele atinse sau arhivate nu contează. */
    fun activeMomenta(
        goalIds: Collection<Long>,
        goals: List<Goal>,
        results: Map<Long, GoalProgress.Result>
    ): List<GoalProgress.Momentum> {
        val active = goals.filter { it.state == Goal.STATE_ACTIVE }.map { it.id }.toSet()
        return goalIds.filter { it in active }.mapNotNull { results[it]?.momentum }
    }
}