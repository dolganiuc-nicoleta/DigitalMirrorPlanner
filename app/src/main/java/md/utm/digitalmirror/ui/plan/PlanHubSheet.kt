package md.utm.digitalmirror.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.utm.digitalmirror.ui.theme.Fraunces

/** Ce poate alege utilizatorul din „+”. */
enum class PlanChoice { WRITE, NOW, WEEK, EVENT }

/**
 * „+” din mijlocul barei: un singur loc pentru tot ce planifici.
 * Fiecare opțiune spune pe înțeles ce face, ca utilizatorul să nu caute prin ecrane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanHubSheet(onChoose: (PlanChoice) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Ce ai de făcut?",
                fontFamily = Fraunces,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                lineHeight = 34.sp
            )
            Text(
                "Alege cum vrei să-l pui în plan. Restul (drumuri, mese, ore) îl potrivesc eu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            ChoiceRow(
                icon = Icons.Outlined.EditNote,
                tint = Color(0xFF17181F), background = Color(0xFFF4E4C9),
                title = "Scrie tot ce ai de făcut",
                hint = "„sună la bancă, sală la 18, temă 2h”",
                emphasized = true,
                onClick = { onChoose(PlanChoice.WRITE) }
            )
            ChoiceRow(
                icon = Icons.Outlined.WbSunny,
                tint = Color(0xFF2F5A2A), background = Color(0xFFE6EFE0),
                title = "Ceva acum",
                hint = "o activitate azi, cu ora propusă",
                onClick = { onChoose(PlanChoice.NOW) }
            )
            ChoiceRow(
                icon = Icons.Outlined.CalendarMonth,
                tint = Color(0xFF8A4E17), background = Color(0xFFF6E3CC),
                title = "Pentru săptămână",
                hint = "„licență 6h”, împărțit pe zile",
                onClick = { onChoose(PlanChoice.WEEK) }
            )
            ChoiceRow(
                icon = Icons.Outlined.Flag,
                tint = Color(0xFF6B3FA0), background = Color(0xFFEEE3F5),
                title = "Un eveniment",
                hint = "examen, susținere, interviu: pași de pregătire",
                onClick = { onChoose(PlanChoice.EVENT) }
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    tint: Color,
    background: Color,
    title: String,
    hint: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (emphasized) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
