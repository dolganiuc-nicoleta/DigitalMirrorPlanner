package md.utm.digitalmirror.ui.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.ui.capsule.CapsuleScreen
import md.utm.digitalmirror.ui.goals.GoalsScreen
import md.utm.digitalmirror.ui.stats.StatsScreen

/**
 * „Oglinda”: tot ce îți arată cum ți-a mers, la un loc. Statisticile (plan vs realitate),
 * obiectivele și capsulele timpului, alese dintr-un selector sus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorScreen(
    initialTab: Int = 0,
    onOpenReport: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, TABS.lastIndex)) }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TABS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index, TABS.size)
                ) { Text(label) }
            }
        }
        // fiecare ecran își are bara lui de titlu; aici doar le alegem
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> StatsScreen(onOpenReport = onOpenReport)
                1 -> GoalsScreen()
                else -> CapsuleScreen()
            }
        }
    }
}

private val TABS = listOf("Statistici", "Obiective", "Capsule")

/** Fila Capsule, pentru notificarea capsulei. */
const val MIRROR_TAB_CAPSULE = 2
