package md.utm.digitalmirror.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.domain.CategoryTidy

/** Iconițele din care alegi pentru o categorie; cheia se păstrează în baza de date (Category.icon). */
object CategoryIcons {

    val ALL: List<Pair<String, ImageVector>> = listOf(
        "school" to Icons.Outlined.School,
        "science" to Icons.Outlined.Science,
        "book" to Icons.AutoMirrored.Outlined.MenuBook,
        "code" to Icons.Outlined.Code,
        "work" to Icons.Outlined.Work,
        "fitness" to Icons.Outlined.FitnessCenter,
        "home" to Icons.Outlined.Home,
        "spa" to Icons.Outlined.Spa,
        "groups" to Icons.Outlined.Groups,
        "restaurant" to Icons.Outlined.Restaurant,
        "palette" to Icons.Outlined.Palette,
        "music" to Icons.Outlined.MusicNote,
        "heart" to Icons.Outlined.FavoriteBorder,
        "travel" to Icons.Outlined.Commute,
        "shopping" to Icons.Outlined.ShoppingCart,
        "pets" to Icons.Outlined.Pets,
        "self" to Icons.Outlined.SelfImprovement,
        "star" to Icons.Outlined.StarOutline
    )

    private val BY_KEY = ALL.toMap()

    fun byKey(key: String?): ImageVector? = key?.let(BY_KEY::get)

    /** Iconița aleasă de tine, altfel cea ghicită din nume; null = punct colorat. */
    fun of(category: Category): ImageVector? = byKey(category.icon ?: CategoryTidy.suggestIcon(category.name))
}
