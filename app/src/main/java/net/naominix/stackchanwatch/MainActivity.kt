package net.naominix.stackchanwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import java.util.Calendar
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

/** 顔バリエーション（スキン）。表情はスキンの内側で切り替える2層構造にする。 */
enum class FaceSkin {
    ORIGINAL, BUBU
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

/** 呼吸。周期は m5stack-avatar の facialLoop 準拠（33ms × 100カウント ≒ 3300ms）。breath は -1〜1。 */
class BreathMotion(private val durationMillis: Long = 3300) : FaceMotion {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackChanFace() {
    var face by remember { mutableStateOf(FaceState()) }
    // 表情はタップで切り替える（フェーズ2）。本家6種を順に巡回する
    var emotion by remember { mutableStateOf(Emotion.NEUTRAL) }
    // 顔バリエーション（ベース）はロングプレスで切り替える
    var skin by remember { mutableStateOf(FaceSkin.ORIGINAL) }
    val motions = remember { listOf(BlinkMotion(), BreathMotion()) }

    // 約33ms 間隔の tick で Motions を合成して適用する（本体の描画間隔に準拠）
    LaunchedEffect(Unit) {
        val tickMillis = 33L
        while (true) {
            var next = FaceState(emotion = emotion, mouthOpen = face.mouthOpen)
            for (motion in motions) {
                next = motion.tick(tickMillis, next)
            }
            face = next
            delay(tickMillis)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {
                    val order = Emotion.entries
                    emotion = order[(order.indexOf(emotion) + 1) % order.size]
                },
                onLongClick = {
                    skin = if (skin == FaceSkin.ORIGINAL) FaceSkin.BUBU else FaceSkin.ORIGINAL
                }
            )
    ) {
        drawFace(face, skin)
    }
}

/** FaceState を Canvas に描く Renderer。スキン（顔バリエーション）ごとに描き分ける。 */
private fun DrawScope.drawFace(face: FaceState, skin: FaceSkin) {
    // 背景は黒（有機EL の省電力も兼ねる）
    drawRect(color = Color.Black)

    val faceW = size.minDimension * 0.88f
    val faceH = faceW * 0.9f
    val left = (size.width - faceW) / 2f
    val top = (size.height - faceH) / 2f
    // 呼吸（本家 Face::draw 準拠：顔の地は固定し、パーツだけが上下する。
    // 目などは breath × 3px / 240、口は自前分と合わせて breath × 5px / 240 相当）
    val partBreathPx = faceH * 0.0125f * face.breath
    val mouthBreathPx = faceH * 0.021f * face.breath
    val cx = size.width / 2f

    if (skin == FaceSkin.BUBU) {
        drawBubuBody(face, cx, top, faceH, partBreathPx, mouthBreathPx)
        drawDigitalTime(cx, top, faceW, faceH)
        return
    }

    // 顔の地（白い角丸）
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(left, top),
        size = Size(faceW, faceH),
        cornerRadius = CornerRadius(faceW * 0.18f, faceW * 0.18f)
    )

    // 目（本家 m5stack-avatar の Eye は真円：直径は画面幅の約5%、中心は幅±22%・高さ40%）
    // 開き具合で円を縦に潰し、閉じきると細い線になる（本家の閉眼表現）
    // 本家のデフォルト Face は眉の太さが0（＝眉なし）で、表情は目の切り欠きだけで表す。
    // DOUBT は本家の Eye に切り欠きがなく、視線を横に流して「考え中」にする。
    val eyeD = faceW * 0.055f
    val eyeCy = top + faceH * 0.40f + partBreathPx
    val doubtGazePx = if (face.emotion == Emotion.DOUBT) faceW * 0.045f else 0f
    val gazePxX = face.gazeX * faceW * 0.03f + doubtGazePx
    val gazePxY = face.gazeY * faceH * 0.03f
    // 本家では画面左の目が isLeft=false（キャラクター基準の右目）になる
    drawEye(cx - faceW * 0.215f + gazePxX, eyeCy + gazePxY, eyeD, face.leftEyeOpen, face.emotion, isLeft = false)
    drawEye(cx + faceW * 0.215f + gazePxX, eyeCy + gazePxY, eyeD, face.rightEyeOpen, face.emotion, isLeft = true)

    // 口（本家 Mouth::draw 準拠：閉じているときは幅28%×高さ2%の細い横棒、
    // 開くにつれて幅が15.6%まで狭まり高さが25%まで伸びる）
    val open = face.mouthOpen.coerceIn(0f, 1f)
    val mouthW = faceW * (0.28f + (0.156f - 0.28f) * open)
    val mouthH = faceH * (0.02f + (0.25f - 0.02f) * open)
    val mouthCy = top + faceH * 0.62f + mouthBreathPx
    drawRoundRect(
        color = Color.Black,
        topLeft = Offset(cx - mouthW / 2f, mouthCy - mouthH / 2f),
        size = Size(mouthW, mouthH),
        cornerRadius = CornerRadius(mouthH / 2f, mouthH / 2f)
    )

    drawDigitalTime(cx, top, faceW, faceH)
}

/** 時刻のデジタル表示。額の位置に小さくグレーで出す（両スキン共通）。 */
private fun DrawScope.drawDigitalTime(cx: Float, top: Float, faceW: Float, faceH: Float) {
    val now = Calendar.getInstance()
    val timeText = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        cx,
        top + faceH * 0.17f,
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0x3A, 0x3A, 0x3A)
            textSize = faceW * 0.072f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    )
}

/**
 * ブブフェイス（顔バリエーション）。今のアバターの特徴で構成する：
 * 大きな丸い白い頭、茶色い丸耳、首まわりの茶色い輪、表面に塗ったピンクのほっぺ。
 * 目はオリジナルと同じ切り欠き方式（地が白なのでそのまま使える）で表情を付け、
 * 口だけブブ用に表情ごとに描き分ける。
 */
private fun DrawScope.drawBubuBody(
    face: FaceState,
    cx: Float,
    top: Float,
    faceH: Float,
    partBreathPx: Float,
    mouthBreathPx: Float
) {
    val d = faceH // 頭の直径
    val headCy = top + faceH / 2f
    val brown = Color(0xFF8A6642)
    val eyeColor = Color(0xFF3B2A20)
    val cheekColor = Color(0xFFF4B8C9)

    // 耳（頭の後ろから覗く茶色い丸耳）
    val earR = d * 0.17f
    val earDx = d * 0.335f
    val earDy = d * 0.345f
    drawCircle(color = brown, radius = earR, center = Offset(cx - earDx, headCy - earDy))
    drawCircle(color = brown, radius = earR, center = Offset(cx + earDx, headCy - earDy))

    // 頭（白い丸）
    drawCircle(color = Color.White, radius = d / 2f, center = Offset(cx, headCy))

    // 首まわりの輪は付けない（2026-11-15 本人判断：顔だけだと大きな口のように見えてしまうため）

    // ほっぺ（頭の表面のピンク着色。独立パーツにはしない＝本人の指定どおり）
    val cheekW = d * 0.17f
    val cheekH = d * 0.105f
    val cheekCy = headCy + d * 0.115f + partBreathPx
    drawOval(
        color = cheekColor,
        topLeft = Offset(cx - d * 0.295f - cheekW / 2f, cheekCy - cheekH / 2f),
        size = Size(cheekW, cheekH)
    )
    drawOval(
        color = cheekColor,
        topLeft = Offset(cx + d * 0.295f - cheekW / 2f, cheekCy - cheekH / 2f),
        size = Size(cheekW, cheekH)
    )

    // 目（切り欠き方式はオリジナルと共通。色だけブブ用に濃い茶色）
    val eyeD = d * 0.082f
    val eyeCy = headCy - d * 0.045f + partBreathPx
    val doubtGazePx = if (face.emotion == Emotion.DOUBT) d * 0.04f else 0f
    drawEye(cx - d * 0.185f + doubtGazePx, eyeCy, eyeD, face.leftEyeOpen, face.emotion, isLeft = false, eyeColor = eyeColor, carveColor = Color.White)
    drawEye(cx + d * 0.185f + doubtGazePx, eyeCy, eyeD, face.rightEyeOpen, face.emotion, isLeft = true, eyeColor = eyeColor, carveColor = Color.White)

    // 口（ブブ用：表情ごとに小さく描き分ける。位置は本人の指摘で少し上へ）
    drawBubuMouth(face.emotion, cx, headCy + d * 0.095f + mouthBreathPx, d, eyeColor)
}

private fun DrawScope.drawBubuMouth(emotion: Emotion, cx: Float, cy: Float, d: Float, color: Color) {
    val w = d * 0.15f
    val h = d * 0.075f
    val stroke = Stroke(width = d * 0.016f, cap = StrokeCap.Round)
    when (emotion) {
        Emotion.HAPPY -> {
            // 開いた笑口（下半分の半円を塗る）
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h)
            )
        }
        Emotion.SAD -> {
            // ∩ 型（困り口）
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h),
                style = stroke
            )
        }
        Emotion.ANGRY -> {
            // 小さなへの字気味の直線
            drawLine(
                color = color,
                start = Offset(cx - w * 0.32f, cy + h * 0.12f),
                end = Offset(cx + w * 0.32f, cy - h * 0.12f),
                strokeWidth = d * 0.016f,
                cap = StrokeCap.Round
            )
        }
        Emotion.SLEEPY -> {
            // 小さな丸（あくび）
            drawOval(
                color = color,
                topLeft = Offset(cx - d * 0.021f, cy - d * 0.021f),
                size = Size(d * 0.042f, d * 0.042f)
            )
        }
        Emotion.DOUBT -> {
            // 横に寄せた小さな直線
            drawLine(
                color = color,
                start = Offset(cx + d * 0.01f, cy),
                end = Offset(cx + d * 0.01f + w * 0.55f, cy),
                strokeWidth = d * 0.016f,
                cap = StrokeCap.Round
            )
        }
        else -> {
            // NEUTRAL：w 型（∪ を2つ並べた猫口、本人の指定 2026-11-15）
            val arcW = w / 2f
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(arcW, h),
                style = stroke
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx, cy - h / 2f),
                size = Size(arcW, h),
                style = stroke
            )
        }
    }
}

private fun DrawScope.drawEye(
    cx: Float,
    cy: Float,
    d: Float,
    open: Float,
    emotion: Emotion,
    isLeft: Boolean,
    eyeColor: Color = Color.Black,
    carveColor: Color = Color.White
) {
    // 真円を縦に潰してまばたきを表す。閉じきっても線として残るよう最小高さを確保
    val h = (d * open.coerceIn(0f, 1f)).coerceAtLeast(d * 0.12f)
    drawOval(
        color = eyeColor,
        topLeft = Offset(cx - d / 2f, cy - h / 2f),
        size = Size(d, h)
    )
    if (open <= 0.05f) return

    // 表情は本家 Eye::draw の「地の色で切り欠く」方式をそのまま踏襲する
    // （顔の地が白なので、白で切り欠けば本家と同じ形になる）
    val rx = d / 2f
    val ry = h / 2f
    val pad = d * 0.12f
    when (emotion) {
        Emotion.ANGRY, Emotion.SAD -> {
            // 上部を三角形に切り欠く。深く切る側は本家の式どおり：
            // Angry は内側、Sad は外側が深くなる
            val x2 = if ((!isLeft) != (emotion != Emotion.SAD)) cx - rx else cx + rx
            val triangle = Path().apply {
                moveTo(cx - rx, cy - ry)
                lineTo(cx + rx, cy - ry)
                lineTo(x2, cy)
                close()
            }
            drawPath(triangle, carveColor)
        }
        Emotion.HAPPY -> {
            // 中心を円で切り欠き、下半分を矩形で切り欠いて「にっこり ∩ 型」にする
            drawOval(
                color = carveColor,
                topLeft = Offset(cx - rx / 1.5f, cy - rx / 1.5f),
                size = Size(rx * 2f / 1.5f, rx * 2f / 1.5f)
            )
            drawRect(
                color = carveColor,
                topLeft = Offset(cx - rx - pad, cy),
                size = Size(d + pad * 2f, ry + pad)
            )
        }
        Emotion.SLEEPY -> {
            // 上半分を矩形で切り欠いて、下向きの三日月（とろんとした目）にする
            drawRect(
                color = carveColor,
                topLeft = Offset(cx - rx - pad, cy - ry),
                size = Size(d + pad * 2f, ry + pad)
            )
        }
        else -> Unit // NEUTRAL / DOUBT は切り欠きなし（DOUBT は視線で表す）
    }
}
