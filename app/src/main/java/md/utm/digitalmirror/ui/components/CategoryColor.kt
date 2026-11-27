package md.utm.digitalmirror.ui.components

import md.utm.digitalmirror.data.entity.Category

/**
 * Culoarea unei categorii. Categoriile create fără culoare aleasă au toate albastrul implicit;
 * ca să se deosebească (pe carduri, în lună, în formular), primesc o culoare din paletă, după id.
 */
object CategoryColor {
    private const val DEFAULT = "#378ADD"

    /** Culorile din care alegi. Albastrul ales de tine e puțin altul decât cel implicit, ca să nu fie înlocuit. */
    val CHOICES = listOf("#3B8FE0", "#2E9E6B", "#E07B39", "#8A5CD1", "#D1508A", "#1E9AA8", "#C9A227", "#D9534F", "#6B7A8F", "#8D6E63")

    private val PALETTE = listOf(
        "#378ADD", // albastru
        "#2E9E6B", // verde
        "#E07B39", // portocaliu
        "#8A5CD1", // mov
        "#D1508A", // roz
        "#1E9AA8", // turcoaz
        "#C9A227", // galben închis
        "#D9534F"  // roșu
    )

    fun hex(category: Category): String =
        if (category.colorHex.equals(DEFAULT, ignoreCase = true)) PALETTE[(category.id % PALETTE.size).toInt()]
        else category.colorHex
}
