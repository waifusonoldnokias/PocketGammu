package com.pocketgammu.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon

/** Rounder-than-default corners: M3 Expressive leans on large, soft containers. */
val PgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()

val PgTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)

/**
 * The expressive shape vocabulary used across PocketGammu. Keeping the mapping in one
 * place means the "personality" of each element stays consistent between pages.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object PgShapeSet {
    val connected: RoundedPolygon get() = MaterialShapes.Sunny
    val connecting: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val disconnected: RoundedPolygon get() = MaterialShapes.Cookie4Sided
    val error: RoundedPolygon get() = MaterialShapes.SoftBurst
    val folder: RoundedPolygon get() = MaterialShapes.Cookie4Sided
    val file: RoundedPolygon get() = MaterialShapes.Square
    val music: RoundedPolygon get() = MaterialShapes.Cookie6Sided
    val image: RoundedPolygon get() = MaterialShapes.Clover4Leaf
    val app: RoundedPolygon get() = MaterialShapes.Pentagon
    val note: RoundedPolygon get() = MaterialShapes.Cookie7Sided
    val selectedNote: RoundedPolygon get() = MaterialShapes.Flower
    val usb: RoundedPolygon get() = MaterialShapes.Gem
    val swatch: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val swatchSelected: RoundedPolygon get() = MaterialShapes.Sunny
}

/** Converts an expressive polygon to a Compose [Shape]. Call from composition only. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberShape(polygon: RoundedPolygon): Shape = polygon.toShape()
