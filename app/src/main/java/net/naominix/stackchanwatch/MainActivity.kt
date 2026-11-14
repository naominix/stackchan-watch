package net.naominix.stackchanwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Stack-chan Watch - Pixel Watch 4 (Wear OS) 向け Stack-chan アバター。
 *
 * 設計方針（プロジェクトの部屋「技術構成」参照）:
 * - 顔の状態は FaceState に集約し、Motions が tick ごとに書き換える
 *   （参考: stack-chan 本体 stack-chan/stack-chan の FaceState + Motions 設計）
 * - 表情の種類は m5stack-avatar の Expression 6種を基準にする
 * - 描画は Renderer（Canvas）が FaceState を受け取るだけにする
 */

enum class Emotion {
    NEUTRAL, HAPPY, ANGRY, SAD, DOUBT, SLEEPY
}

data class FaceState(
    val emotion: Emotion = Emotion.NEUTRAL,
    val mouthOpen: Float = 0f,
    val leftEyeOpen: Float = 1f,
    val rightEyeOpen: Float = 1f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val breath: Float = 1f
)

/** 顔の状態を tick ごとに書き換える小さな処理（Motion）のインタフェース。 */
fun interface FaceMotion {
    fun tick(tickMillis: Long, face: FaceState): FaceState
}

/**
 * まばたき。パラメータとイージングは stack-chan 本体の motions/blink.ts に準拠:
 * 開いている時間 400〜5000ms ランダム、閉じ動作 200〜400ms。
 */
class BlinkMotion(
    private val openMin: Long = 400,
    private val openMax: Long = 5000,
    private val closeMin: Long = 200,
    private val closeMax: Long = 400
) : FaceMotion {
    private var isBlinking = false
    private var nextToggle: Long = Random.nextLong(openMin, openMax)
    private var count: Long = 0

    override fun tick(tickMillis: Long, face: FaceState): FaceState {
        var eyeOpen = 1f
        if (isBlinking) {
            val fraction = linearInEaseOut((count.toFloat() / nextToggle).coerceIn(0f, 1f))
            eyeOpen = 0.2f + fraction * 0.8f
        }
        count += tickMillis
        if (count >= nextToggle) {
            isBlinking = !isBlinking
            count = 0
            nextToggle = if (isBlinking) {
                Random.nextLong(closeMin, closeMax)
            } else {
                Random.nextLong(openMin, openMax)
            }
        }
        return face.copy(
            leftEyeOpen = face.leftEyeOpen * eyeOpen,
            rightEyeOpen = face.rightEyeOpen * eyeOpen
        )
    }

    private fun linearInEaseOut(fraction: Float): Float {
        return if (fraction < 0.25f) {
            1f - fraction * 4f
        } else {
            (fraction - 0.25f).pow(2) * 16f / 9f
        }
    }
}

/** 呼吸（周期 6000ms、stack-chan 本体の BreathMotion に準拠）。breath は -1〜1。 */
class BreathMotion(private val durationMillis: Long = 6000) : FaceMotion {
    private var elapsed: Long = 0
    override fun tick(tickMillis: Long, face: FaceState): FaceState {
        elapsed += tickMillis
        val breath = sin(elapsed.toDouble() / durationMillis * 2.0 * Math.PI).toFloat()
        return face.copy(breath = breath)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StackChanFace()
        }
    }
}

@Composable
fun StackChanFace() {
    var face by remember { mutableStateOf(FaceState()) }
    val motions = remember { listOf(BlinkMotion(), BreathMotion()) }

    // 約33ms 間隔の tick で Motions を合成して適用する（本体の描画間隔に準拠）
    LaunchedEffect(Unit) {
        val tickMillis = 33L
        while (true) {
            var next = FaceState(emotion = face.emotion, mouthOpen = face.mouthOpen)
            for (motion in motions) {
                next = motion.tick(tickMillis, next)
            }
            face = next
            delay(tickMillis)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawFace(face)
    }
}

/** FaceState を Canvas に描く Renderer。当面は Stack-chan オリジナルフェイスのみ。 */
private fun DrawScope.drawFace(face: FaceState) {
    // 背景は黒（有機EL の省電力も兼ねる）
    drawRect(color = Color.Black)

    val faceW = size.minDimension * 0.88f
    val faceH = faceW * 0.9f
    val breathPx = faceH * 0.012f * face.breath
    val left = (size.width - faceW) / 2f
    val top = (size.height - faceH) / 2f + breathPx
    val cx = size.width / 2f

    // 顔の地（白い角丸）
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(left, top),
        size = Size(faceW, faceH),
        cornerRadius = CornerRadius(faceW * 0.18f, faceW * 0.18f)
    )

    // 目（縦長の楕円。開き具合で高さが変わる）
    val eyeW = faceW * 0.085f
    val eyeBaseH = faceH * 0.21f
    val eyeCy = top + faceH * 0.42f
    val gazePxX = face.gazeX * faceW * 0.03f
    val gazePxY = face.gazeY * faceH * 0.03f
    drawEye(cx - faceW * 0.19f + gazePxX, eyeCy + gazePxY, eyeW, eyeBaseH * face.leftEyeOpen.coerceIn(0.05f, 1f))
    drawEye(cx + faceW * 0.19f + gazePxX, eyeCy + gazePxY, eyeW, eyeBaseH * face.rightEyeOpen.coerceIn(0.05f, 1f))

    // 口（mouthOpen が大きいときは楕円、小さいときは笑顔の弧）
    val mouthW = faceW * 0.22f
    val mouthCy = top + faceH * 0.66f
    if (face.mouthOpen > 0.25f) {
        val mouthH = faceH * 0.16f * face.mouthOpen.coerceIn(0f, 1f)
        drawOval(
            color = Color.Black,
            topLeft = Offset(cx - mouthW / 2f, mouthCy - mouthH / 2f),
            size = Size(mouthW, mouthH)
        )
    } else {
        drawArc(
            color = Color.Black,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - mouthW / 2f, mouthCy - faceH * 0.07f),
            size = Size(mouthW, faceH * 0.14f),
            style = Stroke(width = faceW * 0.022f)
        )
    }
}

private fun DrawScope.drawEye(cx: Float, cy: Float, w: Float, h: Float) {
    drawOval(
        color = Color.Black,
        topLeft = Offset(cx - w / 2f, cy - h / 2f),
        size = Size(w, h)
    )
}
