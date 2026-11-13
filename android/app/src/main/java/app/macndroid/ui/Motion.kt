package app.macndroid.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.macndroid.core.Computer
import app.macndroid.core.Haptics
import app.macndroid.core.Link
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** True when "Remove animations" is on in Android's accessibility settings: no looping motion, no confetti. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Seconds since this composable appeared, ticking every frame (stops while the screen isn't drawn). */
@Composable
internal fun rememberClock(running: Boolean = true): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

internal val computerIcon: ImageVector get() = if (Computer.isMac) Icons.Outlined.LaptopMac else Icons.Outlined.Computer

// MARK: Electricity

/**
 * A live electric current between the left and right edges: several thin strands pinned at both ends,
 * wobbling with smooth noise plus a little crackle. [energy] scales how wild it is (0 = a calm straight line).
 */
@Composable
internal fun ElectricLink(
    modifier: Modifier = Modifier,
    energy: Float = 1f,
    strands: Int = 5,
    colors: List<Color> = electricColors(),
) {
    val reduced = rememberReducedMotion()
    val clock by rememberClock(!reduced)
    val seeds = remember { List(strands) { List(3) { Random.nextFloat() * 6.28f } to (0.7f + Random.nextFloat() * 0.8f) } }
    Canvas(modifier) {
        val w = size.width; val mid = size.height / 2; val t = clock
        val step = 3.dp.toPx()
        val flicker = if (reduced) 1f else 0.82f + 0.18f * sin(t * 23f) * sin(t * 7.3f)
        seeds.forEachIndexed { i, (ph, sp) ->
            val amp = if (reduced) 0f else energy * size.height * (0.16f + 0.14f * (0.5f + 0.5f * sin(t * 0.9f + i * 1.7f)))
            val path = Path()
            var x = 0f
            while (x <= w) {
                val u = x / w
                val env = sin(PI.toFloat() * u).toDouble().pow(0.7).toFloat()
                val n = sin(u * 7f + t * 3.1f * sp + ph[0]) * 0.55f + sin(u * 13f - t * 4.7f * sp + ph[1]) * 0.3f +
                    sin(u * 23f + t * 7.9f + ph[2]) * 0.15f + (if (reduced) 0f else (Random.nextFloat() - 0.5f) * 0.18f)
                val y = mid + env * amp * n
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            val c = colors[i % colors.size]
            val core = if (i == 0) 1.5.dp.toPx() else 1.dp.toPx()
            // A soft wide stroke under a thin bright one reads as glow.
            drawPath(path, c.copy(alpha = 0.18f * flicker), style = Stroke(core * 5, cap = StrokeCap.Round))
            drawPath(path, c.copy(alpha = (if (i == 0) 0.95f else 0.6f) * flicker), style = Stroke(core, cap = StrokeCap.Round))
        }
    }
}

/** White, blue and violet on dark; deeper blues and violets on light, so it still shows up. */
@Composable
internal fun electricColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) listOf(Color.White, Color(0xFF8FB4FF), Color(0xFFB794FF), Color(0xFF6FD3FF), Color(0xFFC9B8FF))
    else listOf(Color(0xFF4F46E5), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFF0EA5E9), Color(0xFFA78BFA))
}

// MARK: Devices

/** A device in a rounded tile. Both tiles are the same size, so the phone and the computer look balanced. */
@Composable
internal fun DeviceTile(icon: ImageVector, size: Dp = 88.dp, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(size * 0.29f),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

// MARK: Confetti

private class Piece(
    var x: Float, var y: Float, var vx: Float, var vy: Float, val w: Float, val h: Float,
    var r: Float, val vr: Float, var tilt: Float, val color: Color, val round: Boolean,
)

/** Confetti bursting from both bottom corners. Fires each time [burst] changes to a new non-zero value. */
@Composable
internal fun Confetti(burst: Int, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val pieces = remember { mutableStateListOf<Piece>() }
    var frame by remember { mutableLongStateOf(0L) }
    var bounds by remember { mutableStateOf(Size.Zero) }
    val palette = listOf(0xFF0DA457, 0xFF30D158, 0xFF007AFF, 0xFF5AC8FA, 0xFFFFCC00, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2).map { Color(it) }
    LaunchedEffect(burst) {
        if (burst == 0 || reduced) return@LaunchedEffect
        val (W, H) = snapshotFlow { bounds }.first { it != Size.Zero }
        for (side in 0..1) repeat(90) {
            val ang = (if (side == 1) -PI * 0.62 else -PI * 0.38) + (Random.nextDouble() - 0.5) * 0.55
            val sp = (H / 60f) * (0.9f + Random.nextFloat() * 0.9f)
            pieces += Piece(
                x = if (side == 1) W + 10 else -10f, y = H * 0.95f,
                vx = cos(ang).toFloat() * sp, vy = sin(ang).toFloat() * sp,
                w = 14f + Random.nextFloat() * 14f, h = 7f + Random.nextFloat() * 10f,
                r = Random.nextFloat() * 6f, vr = (Random.nextFloat() - 0.5f) * 0.35f, tilt = Random.nextFloat() * 6f,
                color = palette.random(), round = Random.nextFloat() < 0.25f,
            )
        }
        var life = 0
        while (pieces.isNotEmpty() && life < 300) {
            withFrameNanos { frame = it }
            life++
            val g = H / 2600f
            pieces.forEach { p ->
                p.vy += g; p.vx *= 0.985f; p.vy *= 0.985f; p.x += p.vx; p.y += p.vy; p.r += p.vr; p.tilt += 0.12f
            }
            pieces.removeAll { it.y > H + 60 }
        }
        pieces.clear()
    }
    Canvas(modifier.fillMaxSize().onSizeChanged { bounds = it.toSize() }) {
        frame // redraw every frame
        pieces.forEach { p ->
            translate(p.x, p.y) {
                rotate(p.r * 57.3f, pivot = Offset.Zero) {
                    val sy = abs(cos(p.tilt)).coerceAtLeast(0.15f)
                    if (p.round) drawCircle(p.color, radius = p.h * 0.7f * sy, center = Offset.Zero)
                    else drawRect(p.color, topLeft = Offset(-p.w / 2, -p.h * sy / 2), size = Size(p.w, p.h * sy))
                }
            }
        }
    }
}

// MARK: Connected

/**
 * Shown right after scanning: "Connecting…" with electricity between the phone and the computer, then — the
 * moment the link is up — the two come together, a ✓ pops in, confetti, and a success vibration.
 * The computer shows the same moment at the same time.
 */
@Composable
internal fun PairedScreen(macName: String, onDone: () -> Unit) {
    val status by Link.status.collectAsState()
    val connected = status == Link.Status.Connected
    val failed = status == Link.Status.AuthFailed
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Haptics.play(Haptics.Kind.Tick) }          // the code was read
    LaunchedEffect(connected) {
        if (connected) { Haptics.play(Haptics.Kind.Success); burst++ }
    }
    androidx.activity.compose.BackHandler(onBack = onDone)

    Box(Modifier.fillMaxSize()) {
        Scaffold { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ConnectRow(connected)
                Spacer(Modifier.height(36.dp))
                AnimatedContent(
                    targetState = when { connected -> 2; failed -> 1; else -> 0 },
                    transitionSpec = { fadeIn(tween(400, 250)) togetherWith fadeOut(tween(200)) },
                    label = "pairedText",
                ) { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (s) { 2 -> "You're connected"; 1 -> "Couldn't connect"; else -> "Connecting to $macName" },
                            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when (s) {
                                2 -> "This phone and $macName now work together."
                                1 -> "$macName has a new pairing code. Scan it again."
                                else -> "Keep Mac n Droid open on ${Computer.your}. This takes a few seconds."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (connected) "Continue" else "Continue in the background")
                }
            }
        }
        Confetti(burst)
    }
}

/** Phone tile — connector — computer tile. The connector is electricity while connecting, then a ✓. */
@Composable
internal fun ConnectRow(connected: Boolean, tile: Dp = 88.dp) {
    val reduced = rememberReducedMotion()
    val springy = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
    // The tiles start apart and settle closer once connected.
    val gap by animateDpAsState(if (connected) 104.dp else 140.dp, spring(0.6f, Spring.StiffnessLow), label = "gap")
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, springy) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeviceTile(Icons.Outlined.Smartphone, tile, Modifier.graphicsLayer { translationX = (1 - appear.value) * -60f; alpha = appear.value.coerceIn(0f, 1f) })
        Box(Modifier.width(gap).height(tile), contentAlignment = Alignment.Center) {
            if (!connected) {
                ElectricLink(Modifier.fillMaxWidth().height(tile * 0.6f).padding(horizontal = 6.dp))
            } else {
                val line = remember { Animatable(if (reduced) 1f else 0f) }
                val check = remember { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(Unit) {
                    line.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                    check.animateTo(1f, spring(0.42f, Spring.StiffnessMediumLow))
                }
                val green = Color(0xFF0DA457)
                Canvas(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp)) {
                    val half = size.width / 2 * line.value
                    drawLine(green, Offset(0f, size.height / 2), Offset(half, size.height / 2), size.height, StrokeCap.Round)
                    drawLine(green, Offset(size.width, size.height / 2), Offset(size.width - half, size.height / 2), size.height, StrokeCap.Round)
                }
                Surface(shape = CircleShape, color = green, modifier = Modifier.size(36.dp).scale(check.value)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, "Connected", Modifier.size(20.dp), tint = Color.White) }
                }
            }
        }
        DeviceTile(computerIcon, tile, Modifier.graphicsLayer { translationX = (1 - appear.value) * 60f; alpha = appear.value.coerceIn(0f, 1f) })
    }
}

// MARK: Small pieces

/** A file icon that lifts off towards the computer and fades. Plays each time [trigger] changes. */
@Composable
internal fun FlyAway(trigger: Int, icon: ImageVector, modifier: Modifier = Modifier) {
    val p = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        p.snapTo(0f); p.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    if (p.value < 1f) Icon(
        icon, null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.graphicsLayer {
            translationY = -p.value * 140f; translationX = p.value * 70f
            scaleX = 1f - p.value * 0.45f; scaleY = scaleX
            alpha = 1f - p.value.pow(2)
        },
    )
}

/** A small "Sent to Mac" chip that rises and fades. Plays each time [trigger] changes to a new message. */
@Composable
internal fun SentChip(message: Pair<Int, String>?, modifier: Modifier = Modifier) {
    val p = remember { Animatable(1f) }
    LaunchedEffect(message?.first) {
        if (message == null) return@LaunchedEffect
        p.snapTo(0f); p.animateTo(1f, tween(1800, easing = LinearEasing))
    }
    if (message != null && p.value < 1f) {
        val v = p.value
        val alpha = when { v < 0.12f -> v / 0.12f; v > 0.75f -> (1 - v) / 0.25f; else -> 1f }
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
            modifier = modifier.alpha(alpha).graphicsLayer { translationY = -40f * FastOutSlowInEasing.transform((v / 0.3f).coerceAtMost(1f)) },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                Spacer(Modifier.width(8.dp))
                Text(message.second, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
