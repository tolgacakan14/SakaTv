package com.hrp.sakatv.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hrp.sakatv.R
import java.util.Calendar

/** Siyah tema. Tasarımdaki 1280x720 ölçüsü MainActivity'de birebir dp'ye eşlenir. */
object C {
    val bg = Color(0xFF000000)
    val fg = Color(0xFFF5F5F5)
    val muted = Color(0xFFA3A3A3)
    val dim = Color(0xFF8C8C8C)
    val line = Color(0x38FFFFFF)
    val idle = Color(0x17FFFFFF)
    val focusFg = Color(0xFF0A0A0A)
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),
)

fun inter(size: Int, w: FontWeight = FontWeight.Normal, color: Color = C.fg, ls: Float = 0f) =
    TextStyle(fontFamily = Inter, fontWeight = w, fontSize = size.sp, color = color, letterSpacing = ls.sp)

fun serif(size: Int, w: FontWeight = FontWeight.Normal, color: Color = C.fg) =
    TextStyle(fontFamily = Fraunces, fontWeight = w, fontSize = size.sp, color = color, letterSpacing = (-0.3f).sp, lineHeight = (size * 1.08f).sp)

val EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
const val DUR = 240

enum class Tod { SABAH, OGLE, AKSAM, GECE }

fun currentTod(): Tod {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> Tod.SABAH
        in 11..16 -> Tod.OGLE
        in 17..20 -> Tod.AKSAM
        else -> Tod.GECE
    }
}

val DAYS = listOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
val MONTHS = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

fun pad(n: Int) = if (n < 10) "0$n" else "$n"
