package topview.fileloader.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightMaterialYouScheme = lightColorScheme(
    primary = Color(0xFF345CA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001944),
    secondary = Color(0xFF38656A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBCEBF1),
    onSecondaryContainer = Color(0xFF001F23),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FE),
    onTertiaryContainer = Color(0xFF29132F),
    error = Color(0xFFB3261E),
    background = Color(0xFFF9F8FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE2E2EE),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757680)
)

private val DarkMaterialYouScheme = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6A),
    primaryContainer = Color(0xFF18438F),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFA0CFD4),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF1E4D52),
    onSecondaryContainer = Color(0xFFBCEBF1),
    tertiary = Color(0xFFDDBCE1),
    onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF583E5B),
    onTertiaryContainer = Color(0xFFFAD8FE),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2EA),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2EA),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D2),
    outline = Color(0xFF8F909A)
)

private val FileLoaderTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
internal fun FileLoaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkMaterialYouScheme else LightMaterialYouScheme,
        typography = FileLoaderTypography,
        content = content
    )
}
