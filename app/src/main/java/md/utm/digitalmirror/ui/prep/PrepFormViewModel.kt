package md.utm.digitalmirror.ui.prep

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import md.utm.digitalmirror.domain.PrepTemplates
import java.time.LocalDate

/** Formularul „Eveniment nou”. Stă într-un ViewModel ca să nu se piardă ce a scris utilizatorul la rotirea ecranului. */
class PrepFormViewModel : ViewModel() {

    var title by mutableStateOf("")
    var date by mutableStateOf(LocalDate.now().plusDays(7))
    var templateKey by mutableStateOf(PrepTemplates.ALL.first().key)
        private set
    var totalMinutes by mutableIntStateOf(PrepTemplates.ALL.first().defaultMinutes)
        private set
    var steps by mutableStateOf(PrepTemplates.build(PrepTemplates.ALL.first(), PrepTemplates.ALL.first().defaultMinutes))
        private set

    val stepsTotal: Int get() = steps.sumOf { it.minutes }

    val canSave: Boolean
        get() = title.isNotBlank() && steps.isNotEmpty() && steps.all { it.title.isNotBlank() } &&
                date.isAfter(LocalDate.now())

    /** Alegerea unui șablon reface pașii și timpul total. */
    fun chooseTemplate(key: String) {
        templateKey = key
        totalMinutes = PrepTemplates.byKey(key).defaultMinutes
        rebuild()
    }

    /** Schimbarea totalului refă pașii după șablon (modificările făcute pe pași se pierd). */
    fun changeTotal(delta: Int) {
        totalMinutes = (totalMinutes + delta).coerceIn(MIN_TOTAL, MAX_TOTAL)
        rebuild()
    }

    fun setStepTitle(index: Int, value: String) = update(index) { it.copy(title = value) }

    fun changeStepMinutes(index: Int, delta: Int) =
        update(index) { it.copy(minutes = (it.minutes + delta).coerceAtLeast(STEP)) }

    fun removeStep(index: Int) {
        steps = steps.filterIndexed { i, _ -> i != index }
    }

    fun addStep() {
        steps = steps + PrepTemplates.StepDraft("", 60)
    }

    private fun update(index: Int, change: (PrepTemplates.StepDraft) -> PrepTemplates.StepDraft) {
        steps = steps.mapIndexed { i, s -> if (i == index) change(s) else s }
    }

    private fun rebuild() {
        steps = PrepTemplates.build(PrepTemplates.byKey(templateKey), totalMinutes)
    }

    private companion object {
        const val MIN_TOTAL = 60
        const val MAX_TOTAL = 100 * 60
        const val STEP = 15
    }
}
