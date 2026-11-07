package com.hrp.sakatv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.graphics.Canvas as BitmapCanvas

enum class Mood { UYKU, MERAK, MUTLU }

private val BY_TOD = mapOf(Tod.SABAH to Mood.MERAK, Tod.OGLE to Mood.MUTLU, Tod.AKSAM to Mood.MERAK, Tod.GECE to Mood.UYKU)

fun sakaLine(mood: Mood, tod: Tod, n: Int): String = when (mood) {
    Mood.UYKU -> listOf("Zzz… film başlayınca uyandır.", "Rüyamda ton balığı vardı.")[n % 2]
    Mood.MUTLU -> listOf("Mırr… iyi ki geldin.", "Kafamı kaşı, sonra film seç.")[n % 2]
    Mood.MERAK -> when (tod) {
        Tod.SABAH -> "Günaydın. Mama kabı bana boş göründü."
        Tod.OGLE -> "Güneş tam yatağın üstünde, haberin olsun."
        Tod.AKSAM -> "Bu akşam ne izliyoruz?"
        Tod.GECE -> "Gece yarısı filmi mi? Ben de varım."
    }
}

fun sakaMood(tod: Tod, pokes: Int, forced: Mood? = null): Mood {
    if (forced != null) return forced
    val base = BY_TOD.getValue(tod)
    return Mood.entries[(base.ordinal + pokes) % 3]
}

/**
 * Şaka: kahverengi tekir sokak kedisi. Tasarımdaki SVG'nin birebir Compose çizimi.
 * Çenesi yamuk, alt dudağı koyu, tek dişi görünür, sağ kulağı kesik, badem gözlü, ince koyu tasmalı.
 *
 * Performans: kedinin hareketsiz parçaları boyut başına bir kez bitmap'e pişirilir, her karede sadece
 * birkaç bitmap ve gözler çizilir. Tek bir saat (en fazla ~30 kare/sn, küçük kedide ~15) tüm
 * hareketleri saf matematikle üretir; saat sadece çizim fazında okunur, yeniden kompozisyon olmaz.
 * Uygulama RESUMED değilken ya da animate = false iken saat durur.
 */
@Composable
fun Saka(
    size: Dp,
    tod: Tod,
    mood: Mood,
    modifier: Modifier = Modifier,
    look: Float = 0f,
    mono: Boolean = false,
    line: String? = null,
    lineColor: Color = Color(0xFFBDBDBD),
    zColor: Color = C.fg,
    animate: Boolean = true,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = animate && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Küçük kedi (alt bilgi, detay): sadece nefes ve z, daha seyrek kare.
    val small = size < 160.dp

    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running, small) {
        if (!running) return@LaunchedEffect
        val gap = if (small) 52L else 18L // bir sonraki vsync ile ~15 / ~30 kare/sn
        var base = Long.MIN_VALUE
        while (true) {
            withFrameMillis { ms ->
                if (base == Long.MIN_VALUE) base = ms - clock.longValue
                clock.longValue = ms - base
            }
            delay(gap)
        }
    }

    // Ruh hali geçişleri: State olarak tutulur, değer sadece çizimde okunur.
    val open = animateFloatAsState(if (mood == Mood.MERAK) 1f else 0f, tween(350), label = "o")
    val closed = animateFloatAsState(if (mood == Mood.UYKU) 1f else 0f, tween(350), label = "c")
    val happy = animateFloatAsState(if (mood == Mood.MUTLU) 1f else 0f, tween(350), label = "h")
    val pupil = animateFloatAsState(when (tod) { Tod.GECE -> 8.5f; Tod.AKSAM -> 7f; else -> 5f }, tween(600), label = "p")
    val lookX = animateFloatAsState(look, tween(500, easing = EASE), label = "l")

    val layers = remember(mono) { CatLayers(if (mono) MONO else null) }
    val motion = remember { Motion() }

    Column(modifier.width(size)) {
        if (line != null) {
            Row {
                BasicText("ŞAKA", style = inter(16, FontWeight.SemiBold, lineColor.copy(alpha = 0.7f), ls = 2f))
                Spacer(Modifier.width(10.dp))
                BasicText(line, style = inter(16, color = lineColor.copy(alpha = 0.85f)), maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(260f / 226f).graphicsLayer()) {
            val s = this.size.width / 260f
            if (s <= 0f) return@Canvas
            layers.ensure(s)
            motion.at(clock.longValue, mood, small)
            scale(s, s, pivot = Offset.Zero) {
                drawCat(layers, motion, open, closed, happy, pupil, lookX, zColor)
            }
        }
    }
}

// ---------------- hareket: tek saatten saf matematik ----------------

/** Eski InfiniteTransition'ların birebir karşılığı (aynı süreler, aynı eğriler). */
private class Motion {
    var tail = 0f; var breath = 1f; var head = 0f; var blink = 1f; var ear = 0f; var float = 0f

    fun at(t: Long, mood: Mood, small: Boolean) {
        val sleepy = mood == Mood.UYKU
        breath = 1f + (if (sleepy) 0.035f else 0.025f) * pingPong(t, if (sleepy) 2500 else 2100)
        float = (t % 3000L).toFloat() / 3000f
        if (small) { tail = 0f; head = 0f; blink = 1f; ear = 0f; return }
        val tailDur = when (mood) { Mood.MUTLU -> 1200; Mood.UYKU -> 7000; Mood.MERAK -> 3400 }
        tail = -14f * pingPong(t, tailDur / 2)
        head = -3f * pingPong(t, 4500)
        blink = keys((t % 6000L).toFloat(), BLINK)
        ear = keys((t % 8000L).toFloat(), EAR)
    }
}

/** tween(half, FastOutSlowIn) + RepeatMode.Reverse: 0→1→0, bir tur 2*half ms. */
private fun pingPong(t: Long, half: Int): Float {
    val p = (t % (half * 2L)).toFloat() / half
    return FastOutSlowInEasing.transform(if (p <= 1f) p else 2f - p)
}

/** Doğrusal ara değerli anahtar kareler: [zaman, değer] çiftleri, zamana göre sıralı. */
private fun keys(t: Float, k: FloatArray): Float {
    if (t <= k[0]) return k[1]
    var i = 0
    while (i + 2 < k.size) {
        val t0 = k[i]; val v0 = k[i + 1]; val t1 = k[i + 2]; val v1 = k[i + 3]
        if (t <= t1) return v0 + (v1 - v0) * ((t - t0) / (t1 - t0))
        i += 2
    }
    return k[k.size - 1]
}

private val BLINK = floatArrayOf(0f, 1f, 5580f, 1f, 5760f, 0.08f, 6000f, 1f)
private val EAR = floatArrayOf(0f, 0f, 7040f, 0f, 7360f, -10f, 7680f, 4f, 8000f, 0f)

private val MONO: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

// ---------------- çizim ----------------

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()
private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
private fun hex(v: Long) = Color(v)

private class Grad(val cx: Float, val cy: Float, val r: Float, val stops: Array<Pair<Float, Color>>)

private val G_BODY = Grad(0.42f, 0.3f, 0.8f, arrayOf(0f to hex(0xFF8A7457), 0.6f to hex(0xFF6C5841), 1f to hex(0xFF4A3C2C)))
private val G_HEAD = Grad(0.45f, 0.35f, 0.75f, arrayOf(0f to hex(0xFF94795A), 0.7f to hex(0xFF735F45), 1f to hex(0xFF55452F)))
private val G_EYE = Grad(0.5f, 0.45f, 0.6f, arrayOf(0f to hex(0xFFE4D36A), 0.7f to hex(0xFFB3A63F), 1f to hex(0xFF6F6A24)))
private val G_EAR = Grad(0.5f, 0.7f, 0.7f, arrayOf(0f to hex(0xFFE7B2A6), 1f to hex(0xFFB98478)))

private object Art {
    val shadow = oval(140f, 216f, 118f, 9f)
    val tail = p("M236 150 C 266 178, 240 216, 182 212")
    val body = oval(156f, 152f, 102f, 60f)
    val spine = p("M112 98 C 160 86, 210 100, 248 138")
    val stripes = p("M150 96 q11 22 6 38 q-4 12 0 22 M178 99 q12 24 5 40 q-3 12 1 24 M206 108 q10 20 4 34 q-3 10 0 20 M231 124 q7 16 2 34")
    val stripesThin = p("M164 104 q8 20 3 34 q-2 8 0 16 M192 104 q8 22 3 36 q-2 9 0 18 M219 116 q7 18 2 30")
    val belly = oval(150f, 186f, 70f, 18f)
    val paw1 = oval(62f, 200f, 24f, 12f)
    val toes1 = p("M50 204 l0 -6 M60 206 l0 -7 M70 205 l0 -6")
    val paw2 = oval(124f, 204f, 24f, 11f)
    val toes2 = p("M112 207 l0 -6 M122 209 l0 -7 M132 208 l0 -6")
    val earL = p("M42 94 C 37 66, 39 40, 46 16 C 60 28, 78 44, 90 60 Z")
    val earLIn = p("M50 82 C 47 64, 49 46, 52 30 C 62 40, 72 50, 80 60 Z")
    val earLFur = p("M56 70 l7 -5 M58 78 l8 -3 M55 62 l6 -6")
    val earR = p("M102 60 C 111 48, 120 38, 128 30 L 148 22 C 152 42, 153 66, 150 94 Z")
    val earRCut = p("M128 30 L 148 22")
    val earRIn = p("M110 61 C 117 52, 124 44, 131 37 L 143 33 C 146 48, 145 64, 143 80 Z")
    val earRFur = p("M134 66 l-7 -5 M136 74 l-8 -3")
    val head = p("M37 102 C 35 70, 60 54, 96 54 C 132 54, 157 70, 155 102 C 157 126, 150 147, 130 159 C 117 166, 75 166, 62 159 C 42 147, 35 126, 37 102 Z")
    val mask = p("M58 95 C 70 86, 122 86, 134 95 C 129 112, 117 121, 107 133 C 101 141, 91 141, 85 133 C 75 121, 63 112, 58 95 Z")
    val mStripes = p("M80 68 q3 8 4 15 M88 64 q2 10 2 18 M96 62 l0 22 M104 64 q-2 10 -2 18 M112 68 q-3 8 -4 15")
    val brow = p("M70 74 q4 4 6 10 M122 74 q-4 4 -6 10")
    val cheekLines = p("M57 97 q-12 4 -22 15 M61 110 q-11 6 -19 17 M135 97 q12 4 22 15 M131 110 q11 6 19 17")
    val nose = p("M90 92 Q 96 116 102 92")
    val underEye = p("M62 107 Q 73 114 87 104 M130 107 Q 119 114 105 104")
    val eyeL = p("M57 97 Q 70 85 87 100 Q 79 111 67 108 Q 60 105 57 97 Z")
    val eyeR = p("M135 97 Q 122 85 105 100 Q 113 111 125 108 Q 132 105 135 97 Z")
    val lids = p("M55 96 Q 70 81 89 99 Q 72 90 55 96 Z M137 96 Q 122 81 103 99 Q 120 90 137 96 Z")
    val closedEyes = p("M57 99 Q 72 108 87 101 M135 99 Q 120 108 105 101")
    val happyFill = p("M58 101 Q 70 96 86 102 Q 78 109 67 107 Q 61 105 58 101 Z M134 101 Q 122 96 106 102 Q 114 109 125 107 Q 131 105 134 101 Z")
    val happyLine = p("M57 100 Q 72 94 87 102 M135 100 Q 120 94 105 102")
    val corners = p("M57 97 q -8 1 -13 6 M135 97 q 8 1 13 6")
    val ruff = p("M38 122 l-12 7 l12 1 l-9 8 l14 -2 l-6 8 l13 -6 M154 122 l12 7 l-12 1 l9 8 l-14 -2 l6 8 l-13 -6")
    val bib = p("M76 158 l-4 8 l6 -3 l0 7 l5 -6 l3 7 l3 -7 l5 6 l0 -7 l6 3 l-4 -8 z")
    val muzzleL = oval(85f, 131f, 11f, 7f)
    val muzzleR = oval(107f, 131f, 11f, 7f)
    val chin = oval(97f, 150f, 10f, 6f)
    val chinSpot = oval(104f, 147f, 4.2f, 2.8f)
    val noseTip = p("M90 120 Q 96 117 102 120 Q 100 126 96 128 Q 92 126 90 120 Z")
    val mouth = p("M96 128 L 96 133 M96 133 Q 90 138 82 135 M96 133 Q 101 135 107 132")
    val lip = p("M87 138 Q 98 145 108 137 Q 104 142 98 143 Q 92 143 87 138 Z")
    val lipShade = oval(92f, 137.5f, 4.5f, 1.8f)
    val tooth = p("M85 135.5 l2.4 5.6 l2 -5.4 z")
    val whiskers = p("M78 133 Q 42 124 14 127 M78 137 Q 44 138 16 147 M114 133 Q 150 124 178 127 M114 137 Q 148 138 176 147")
    val collar = p("M56 161 q40 20 80 0")
    val tag = p("M96 172 l-5 7 l5 5 l5 -5 z")
    val z1 = p("M168 32 h11 l-11 14 h11")
    val z2 = p("M184 6 h15 l-15 20 h15")
    val heart = p("M172 40 c-6 -8 -18 -2 -12 8 l12 10 l12 -10 c6 -10 -6 -16 -12 -8 z")
}

// Fırçalar bir kez hesaplanır; her karede yeniden üretilmez.
private val BRUSHES = HashMap<Path, Brush>()

private fun DrawScope.fill(path: Path, color: Color, alpha: Float = 1f, cf: ColorFilter? = null) =
    drawPath(path, color, alpha, colorFilter = cf)

private fun DrawScope.fillG(path: Path, g: Grad, alpha: Float = 1f, cf: ColorFilter? = null) {
    val brush = BRUSHES.getOrPut(path) {
        val b = path.getBounds()
        Brush.radialGradient(
            colorStops = *g.stops,
            center = Offset(b.left + g.cx * b.width, b.top + g.cy * b.height),
            radius = g.r * maxOf(b.width, b.height),
        )
    }
    drawPath(path, brush, alpha, colorFilter = cf)
}

private fun DrawScope.line(
    path: Path, color: Color, w: Float, alpha: Float = 1f, dash: PathEffect? = null, round: Boolean = true, cf: ColorFilter? = null,
) = drawPath(
    path, color, alpha,
    Stroke(width = w, cap = if (round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = dash),
    colorFilter = cf,
)

private val DASH = PathEffect.dashPathEffect(floatArrayOf(7f, 13f))

// ---------------- pişmiş katmanlar ----------------

/** Tasarım birimindeki tam sayı dikdörtgen + o bölgenin piksel boyutunda bitmap'i. */
private class Baked(val r: IntRect, val img: ImageBitmap)

private fun bounds(pad: Float, vararg ps: Path): IntRect {
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (p in ps) {
        val x = p.getBounds()
        if (x.left < l) l = x.left
        if (x.top < t) t = x.top
        if (x.right > r) r = x.right
        if (x.bottom > b) b = x.bottom
    }
    return IntRect(floor(l - pad).toInt(), floor(t - pad).toInt(), ceil(r + pad).toInt(), ceil(b + pad).toInt())
}

private fun bake(r: IntRect, s: Float, block: DrawScope.() -> Unit): Baked {
    val w = ceil(r.width * s).toInt().coerceAtLeast(1)
    val h = ceil(r.height * s).toInt().coerceAtLeast(1)
    val img = ImageBitmap(w, h)
    val sx = w / r.width.toFloat()
    val sy = h / r.height.toFloat()
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(img), Size(w.toFloat(), h.toFloat())) {
        scale(sx, sy, pivot = Offset.Zero) {
            translate(-r.left.toFloat(), -r.top.toFloat()) { block() }
        }
    }
    return Baked(r, img)
}

/** Tasarım biriminde (scale(s) içinde) çağrılır. */
private fun DrawScope.put(b: Baked) = drawImage(b.img, dstOffset = b.r.topLeft, dstSize = b.r.size)

private object Bounds {
    val tail = bounds(10f, Art.tail)
    val body = bounds(4f, Art.body, Art.spine, Art.stripes, Art.stripesThin, Art.belly)
    val paws = bounds(3f, Art.paw1, Art.paw2, Art.toes1, Art.toes2)
    val earR = bounds(3f, Art.earR, Art.earRCut, Art.earRIn, Art.earRFur)
    val face = bounds(4f, Art.earL, Art.earLIn, Art.earLFur, Art.head, Art.mask, Art.mStripes, Art.brow, Art.cheekLines, Art.nose, Art.underEye)
    val front = bounds(
        5f, Art.corners, Art.ruff, Art.bib, Art.muzzleL, Art.muzzleR, Art.chin, Art.chinSpot, Art.noseTip,
        Art.mouth, Art.lip, Art.lipShade, Art.tooth, Art.whiskers, Art.collar, Art.tag,
    )
}

/**
 * Kedinin birbirine göre kıpırdamayan parçaları. Her biri kendi dönüşümüyle (kuyruk dönmesi,
 * nefes, kulak seğirmesi, baş eğilmesi) tek bir drawImage olarak çizilir.
 * mono: ekran koruyucu için renksiz; filtre pişirirken uygulanır, karede saveLayer gerekmez.
 */
private class CatLayers(val cf: ColorFilter?) {
    private var scale = -1f
    lateinit var tail: Baked
    lateinit var body: Baked
    lateinit var paws: Baked
    lateinit var earR: Baked
    lateinit var face: Baked
    lateinit var front: Baked

    fun ensure(s: Float) {
        if (s == scale) return
        scale = s
        val a = Art
        tail = bake(Bounds.tail, s) {
            line(a.tail, hex(0xFF6C5841), 17f, cf = cf)
            line(a.tail, hex(0xFF241B12), 17f, 0.8f, DASH, round = false, cf = cf)
            drawCircle(hex(0xFF1D160F), 8.5f, Offset(182f, 212f), colorFilter = cf)
        }
        body = bake(Bounds.body, s) {
            fillG(a.body, G_BODY, cf = cf)
            line(a.spine, hex(0xFF3A2E21), 5f, 0.7f, cf = cf)
            line(a.stripes, hex(0xFF241B12), 5f, 0.7f, cf = cf)
            line(a.stripesThin, hex(0xFF3A2E21), 2f, 0.35f, cf = cf)
            fill(a.belly, hex(0xFFA8916F), 0.55f, cf)
        }
        paws = bake(Bounds.paws, s) {
            fill(a.paw1, hex(0xFF8F7959), cf = cf); line(a.toes1, hex(0xFF5A4835), 2f, cf = cf)
            fill(a.paw2, hex(0xFF8F7959), cf = cf); line(a.toes2, hex(0xFF5A4835), 2f, cf = cf)
        }
        earR = bake(Bounds.earR, s) {
            fill(a.earR, hex(0xFF76624A), cf = cf)
            line(a.earRCut, hex(0xFF5A4936), 2.2f, cf = cf)
            fillG(a.earRIn, G_EAR, cf = cf)
            line(a.earRFur, hex(0xFFEFE4D2), 1.1f, 0.85f, cf = cf)
        }
        face = bake(Bounds.face, s) {
            fill(a.earL, hex(0xFF76624A), cf = cf); fillG(a.earLIn, G_EAR, cf = cf); line(a.earLFur, hex(0xFFEFE4D2), 1.1f, 0.85f, cf = cf)
            fillG(a.head, G_HEAD, cf = cf)
            fill(a.mask, hex(0xFFA68D68), 0.45f, cf)
            line(a.mStripes, hex(0xFF3A2E21), 2.4f, 0.85f, cf = cf)
            line(a.brow, hex(0xFF3A2E21), 2f, 0.6f, cf = cf)
            line(a.cheekLines, hex(0xFF2E241A), 2.6f, 0.85f, cf = cf)
            fill(a.nose, hex(0xFFC4AB83), 0.55f, cf)
            line(a.underEye, hex(0xFFE6D6B8), 2.2f, 0.8f, cf = cf)
        }
        front = bake(Bounds.front, s) {
            line(a.corners, hex(0xFF1D160F), 2.2f, cf = cf)
            fill(a.ruff, hex(0xFF806A4D), cf = cf)
            fill(a.bib, hex(0xFFB9A382), 0.6f, cf)
            fill(a.muzzleL, hex(0xFFCDB894), 0.55f, cf)
            fill(a.muzzleR, hex(0xFFCDB894), 0.55f, cf)
            rotate(6f, pivot = Offset(97f, 150f)) { fill(a.chin, hex(0xFFD8CCB6), cf = cf) }
            rotate(12f, pivot = Offset(104f, 147f)) { fill(a.chinSpot, hex(0xFF2A2019), 0.85f, cf) }
            fill(a.noseTip, hex(0xFFA85F52), cf = cf); line(a.noseTip, hex(0xFF2E211A), 1.4f, cf = cf)
            line(a.mouth, hex(0xFF2A1F17), 1.8f, cf = cf)
            fill(a.lip, hex(0xFF231A14), cf = cf)
            fill(a.lipShade, hex(0xFF140D09), cf = cf)
            fill(a.tooth, hex(0xFFFBF7EE), cf = cf); line(a.tooth, hex(0xFFBDB3A2), 0.5f, cf = cf)
            for (c in WHISKER_DOTS) drawCircle(hex(0xFF3E3024), 0.9f, c, colorFilter = cf)
            line(a.whiskers, hex(0xFFF3ECE0), 1f, 0.7f, cf = cf)
            line(a.collar, hex(0xFF161616), 5.5f, cf = cf)
            fill(a.tag, hex(0xFFC9CCD2), cf = cf); line(a.tag, hex(0xFF8A8E96), 1f, cf = cf)
        }
    }
}

private val WHISKER_DOTS = listOf(Offset(81f, 130f), Offset(84f, 134f), Offset(111f, 130f), Offset(108f, 134f))
private val SHADOW = Color(0x38000000)
private val PUPIL_SIZE_H = 20f

// ---------------- kare ----------------

private fun DrawScope.drawCat(
    L: CatLayers, m: Motion,
    openS: State<Float>, closedS: State<Float>, happyS: State<Float>, pupilS: State<Float>, lookS: State<Float>,
    zColor: Color,
) {
    val a = Art
    val cf = L.cf
    val open = openS.value
    val closed = closedS.value
    val happy = happyS.value

    fill(a.shadow, SHADOW)

    if (m.tail != 0f) rotate(m.tail, pivot = Offset(236f, 152f)) { put(L.tail) } else put(L.tail)

    scale(1f, m.breath, pivot = Offset(156f, 212f)) { put(L.body) }

    put(L.paws)

    rotate(m.head, pivot = Offset(96f, 167f)) {
        if (m.ear != 0f) rotate(m.ear, pivot = Offset(112f, 94f)) { put(L.earR) } else put(L.earR)
        put(L.face)

        if (open > 0.01f) {
            val pupil = pupilS.value
            val look = lookS.value
            withTransform({
                scale(1.12f, 1.12f, pivot = Offset(96f, 99f))
                scale(1f, m.blink, pivot = Offset(96f, 97f))
            }) {
                fillG(a.eyeL, G_EYE, open, cf); fillG(a.eyeR, G_EYE, open, cf)
                line(a.eyeL, hex(0xFF1D160F), 2.6f, open, cf = cf)
                line(a.eyeR, hex(0xFF1D160F), 2.6f, open, cf = cf)
                val pc = hex(0xFF100B07)
                drawOval(pc, Offset(72f + look - pupil, 89.5f), Size(pupil * 2, PUPIL_SIZE_H), open, colorFilter = cf)
                drawOval(pc, Offset(120f + look - pupil, 89.5f), Size(pupil * 2, PUPIL_SIZE_H), open, colorFilter = cf)
                fill(a.lids, hex(0xFF735F45), open, cf)
                drawCircle(Color.White, 2.2f, Offset(76f, 95.5f), 0.9f * open)
                drawCircle(Color.White, 2.2f, Offset(124f, 95.5f), 0.9f * open)
            }
        }
        if (closed > 0.01f) line(a.closedEyes, hex(0xFF1D160F), 3f, closed, cf = cf)
        if (happy > 0.01f) {
            fillG(a.happyFill, G_EYE, happy, cf)
            line(a.happyLine, hex(0xFF1D160F), 2.8f, happy, cf = cf)
        }

        put(L.front)
    }

    // z ve kalp: yükselip kaybolur
    val f = m.float
    val fa = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
    if (closed > 0.01f || happy > 0.01f) translate(16f * f, 6f - 40f * f) {
        if (closed > 0.01f) {
            line(a.z1, zColor, 3.2f, fa * closed)
            line(a.z2, zColor, 4.2f, fa * closed)
        }
        if (happy > 0.01f) fill(a.heart, zColor, 0.85f * fa * happy)
    }
}
