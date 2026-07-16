package com.sunnypilot.toolbox.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.sunnypilot.toolbox.data.repository.HudData

@Composable
fun HudOverlay(
    hudData: HudData,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        // 1. 车速显示 (左上角)
        drawText(
            text = "${hudData.speed.toInt()} km/h",
            x = 32f,
            y = 80f,
            paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 72f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(8f, 2f, 2f, android.graphics.Color.BLACK)
            }
        )
        
        // 2. 档位显示 (左上角下方)
        drawText(
            text = hudData.gear,
            x = 32f,
            y = 160f,
            paint = Paint().apply {
                color = android.graphics.Color.CYAN
                textSize = 48f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
            }
        )
        
        // 3. 转向灯指示
        if (hudData.leftBlinker) {
            drawCircle(
                color = Color.Green,
                radius = 20f,
                center = Offset(100f, 200f)
            )
            drawText(
                text = "◀",
                x = 85f,
                y = 210f,
                paint = Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 40f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
        }
        if (hudData.rightBlinker) {
            drawCircle(
                color = Color.Green,
                radius = 20f,
                center = Offset(width - 100f, 200f)
            )
            drawText(
                text = "▶",
                x = width - 115f,
                y = 210f,
                paint = Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 40f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
        }
        
        // 4. 前车距离 (中上方)
        hudData.leadDistance?.let { distance ->
            if (distance > 0) {
                drawText(
                    text = "前车 ${distance.toInt()}m",
                    x = width / 2 - 100f,
                    y = 80f,
                    paint = Paint().apply {
                        color = when {
                            distance < 15 -> android.graphics.Color.RED
                            distance < 30 -> android.graphics.Color.YELLOW
                            else -> android.graphics.Color.GREEN
                        }
                        textSize = 40f
                        typeface = Typeface.DEFAULT_BOLD
                        setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
                    }
                )
            }
        }
        
        // 5. 横向控制状态指示 (中央)
        if (hudData.enabled) {
            drawText(
                text = "✓ 横向控制",
                x = width / 2 - 120f,
                y = height / 2 - 200f,
                paint = Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 36f
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
                }
            )
        }
        
        // 6. 转向角度 (右上角)
        drawText(
            text = "转向 ${hudData.steeringAngle.toInt()}°",
            x = width - 200f,
            y = 80f,
            paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
            }
        )
        
        // 7. 车道线指示 (底部)
        val laneY = height - 100f
        hudData.laneLeft?.let {
            drawLine(
                color = Color.Yellow,
                start = Offset(width * 0.3f, laneY),
                end = Offset(width * 0.3f, laneY - 150f),
                strokeWidth = 6f
            )
        }
        hudData.laneRight?.let {
            drawLine(
                color = Color.Yellow,
                start = Offset(width * 0.7f, laneY),
                end = Offset(width * 0.7f, laneY - 150f),
                strokeWidth = 6f
            )
        }
        
        // 8. 警告信息 (顶部中央)
        if (hudData.alertText1.isNotEmpty()) {
            val alertColor = when (hudData.alertStatus) {
                "critical" -> android.graphics.Color.RED
                "userPrompt" -> android.graphics.Color.YELLOW
                else -> android.graphics.Color.WHITE
            }
            drawText(
                text = hudData.alertText1,
                x = width / 2 - 200f,
                y = 200f,
                paint = Paint().apply {
                    color = alertColor
                    textSize = 44f
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(8f, 2f, 2f, android.graphics.Color.BLACK)
                }
            )
        }
        
        // 9. 刹车指示 (右下角)
        if (hudData.brakeLights) {
            drawCircle(
                color = Color.Red,
                radius = 30f,
                center = Offset(width - 60f, height - 60f)
            )
            drawText(
                text = "刹车",
                x = width - 90f,
                y = height - 45f,
                paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
        }
    }
}

// 辅助函数：Canvas绘制文字
private fun DrawScope.drawText(text: String, x: Float, y: Float, paint: Paint) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
