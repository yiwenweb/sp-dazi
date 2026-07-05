package com.sunnypilot.toolbox.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 简化版 ANSI 转义序列解析器。
 * 仅处理 SGR（颜色/样式）序列，其他 CSI / OSC 序列会被过滤掉。
 */
object AnsiParser {

    // 匹配 CSI (ESC[...X) 和 OSC (ESC]...BEL) 序列
    private val ansiRegex = Regex("\u001B\\[[\\d;]*[a-zA-Z]|\u001B\\][^\u0007]*\u0007|\u001B\\([\\dA-Za-z]")

    private val foregroundColors: Map<Int, Color> = mapOf(
        30 to Color(0xFF000000),
        31 to Color(0xFFEF4444),
        32 to Color(0xFF22C55E),
        33 to Color(0xFFEAB308),
        34 to Color(0xFF3B82F6),
        35 to Color(0xFFA855F7),
        36 to Color(0xFF06B6D4),
        37 to Color(0xFFF3F4F6),
        90 to Color(0xFF4B5563),
        91 to Color(0xFFFCA5A5),
        92 to Color(0xFF86EFAC),
        93 to Color(0xFFFDE047),
        94 to Color(0xFF93C5FD),
        95 to Color(0xFFD8B4FE),
        96 to Color(0xFF67E8F9),
        97 to Color(0xFFFFFFFF)
    )

    private val backgroundColors: Map<Int, Color> = mapOf(
        40 to Color(0xFF000000),
        41 to Color(0xFFEF4444),
        42 to Color(0xFF22C55E),
        43 to Color(0xFFEAB308),
        44 to Color(0xFF3B82F6),
        45 to Color(0xFFA855F7),
        46 to Color(0xFF06B6D4),
        47 to Color(0xFFF3F4F6),
        100 to Color(0xFF4B5563),
        101 to Color(0xFFFCA5A5),
        102 to Color(0xFF86EFAC),
        103 to Color(0xFFFDE047),
        104 to Color(0xFF93C5FD),
        105 to Color(0xFFD8B4FE),
        106 to Color(0xFF67E8F9),
        107 to Color(0xFFFFFFFF)
    )

    fun parse(text: String): AnnotatedString = buildAnnotatedString {
        var currentStyle = SpanStyle()
        var lastIndex = 0

        for (match in ansiRegex.findAll(text)) {
            if (match.range.first > lastIndex) {
                withStyle(currentStyle) {
                    append(text.substring(lastIndex, match.range.first))
                }
            }

            if (match.value.endsWith('m')) {
                currentStyle = applySgr(match.value, currentStyle)
            }
            // 其他 CSI / OSC 序列直接丢弃

            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            withStyle(currentStyle) {
                append(text.substring(lastIndex))
            }
        }
    }

    private fun applySgr(seq: String, base: SpanStyle): SpanStyle {
        val body = seq.removePrefix("\u001B[").removeSuffix("m")
        if (body.isEmpty()) return SpanStyle()

        val codes = body.split(";").map { it.toIntOrNull() ?: 0 }
        var style = base

        for (code in codes) {
            when {
                code == 0 -> style = SpanStyle()
                code == 1 -> style = style.copy(fontWeight = FontWeight.Bold)
                code == 22 -> style = style.copy(fontWeight = FontWeight.Normal)
                code in 30..37 -> style = style.copy(color = foregroundColors[code] ?: Color.Unspecified)
                code in 90..97 -> style = style.copy(color = foregroundColors[code] ?: Color.Unspecified)
                code in 40..47 -> style = style.copy(background = backgroundColors[code] ?: Color.Unspecified)
                code in 100..107 -> style = style.copy(background = backgroundColors[code] ?: Color.Unspecified)
            }
        }
        return style
    }
}
