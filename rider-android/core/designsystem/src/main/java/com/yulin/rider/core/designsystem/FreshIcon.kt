package com.yulin.rider.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class FreshIconType {
    BACK,
    CHEVRON_RIGHT,
    CHEVRON_DOWN,
    CHECK,
    CLOSE,
    WARNING,
    ERROR,
    INFO,
    OFFLINE,
    COLD,
    CAMERA,
    PHONE,
    MAP,
    ROUTE,
    TASK,
    PICKUP,
    DELIVERY,
    MESSAGE,
    PROFILE,
    STATS,
    SETTINGS,
    ABOUT,
    BATTERY,
    LOCK,
    LOCATION,
    STORE,
    RIDER,
    PACKAGE,
    CLOCK,
    REFRESH,
    HOME,

    // 美团骑手端特有
    MENU,
    BELL,
    CHAT,
    PROBLEM,
    NAVIGATE,
    LOCATE,
    FEEDBACK,
    SORT,
    SHIELD,
    TIMER,
    BOLT,
    WALLET,
    HEADSET,
    ADD,
    WALK,
    EBIKE,
    CAR,
}

/**
 * 统一图标。
 *
 * 路径按 Material Symbols 的 24 网格与光学重量绘制，同一屏里所有图标视觉重量一致。
 * 此前是逐个手写坐标的 Canvas 线条图，笔画粗细与留白各不相同。
 *
 * [contentDescription] 为空时作为装饰图；操作入口必须传入可朗读的中文动作名。
 */
@Composable
fun FreshIcon(
    type: FreshIconType,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    Icon(
        imageVector = FreshIconVectors.getValue(type),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}

private fun vector(
    name: String,
    vararg pathData: String,
    autoMirror: Boolean = false,
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )
    pathData.forEach { data ->
        builder.addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
    }
    return builder.build()
}

/** 图标只在首次取用时构建一次；ImageVector 构建放进重组路径会白白吃掉滚动帧。 */
private val FreshIconVectors: Map<FreshIconType, ImageVector> by lazy {
    mapOf(
        FreshIconType.BACK to vector(
            "back",
            "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
            autoMirror = true,
        ),
        FreshIconType.CHEVRON_RIGHT to vector(
            "chevronRight",
            "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z",
            autoMirror = true,
        ),
        FreshIconType.CHEVRON_DOWN to vector(
            "chevronDown",
            "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z",
        ),
        FreshIconType.CHECK to vector(
            "check",
            "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
        ),
        FreshIconType.CLOSE to vector(
            "close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 " +
                "17.59,19 19,17.59 13.41,12z",
        ),
        FreshIconType.WARNING to vector(
            "warning",
            "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z",
        ),
        FreshIconType.ERROR to vector(
            "error",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2z" +
                "M13,13h-2L11,7h2v6z",
        ),
        FreshIconType.INFO to vector(
            "info",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6z" +
                "M13,9h-2L11,7h2v2z",
        ),
        FreshIconType.PROBLEM to vector(
            "problem",
            "M11,15h2v2h-2zM11,7h2v6h-2zM11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 " +
                "22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        ),
        FreshIconType.OFFLINE to vector(
            "offline",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 " +
                "11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 " +
                "-1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96z" +
                "M3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 " +
                "3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
        ),
        FreshIconType.COLD to vector(
            "cold",
            "M22,11h-4.17l3.24,-3.24 -1.41,-1.42L15,11h-2V9l4.66,-4.66 -1.42,-1.41L13,6.17V2h-2v4.17" +
                "L7.76,2.93 6.34,4.34 11,9v2H9L4.34,6.34 2.93,7.76 6.17,11H2v2h4.17l-3.24,3.24 " +
                "1.41,1.42L9,13h2v2l-4.66,4.66 1.42,1.41L11,17.83V22h2v-4.17l3.24,3.24 1.42,-1.41L13,15v-2h2" +
                "l4.66,4.66 1.41,-1.42L17.83,13H22v-2z",
        ),
        FreshIconType.CAMERA to vector(
            "camera",
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 " +
                "-0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 " +
                "-2.24,5 -5,5zM12,9c-1.65,0 -3,1.35 -3,3s1.35,3 3,3 3,-1.35 3,-3 -1.35,-3 -3,-3z",
        ),
        FreshIconType.PHONE to vector(
            "phone",
            "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 " +
                "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 " +
                "-17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 " +
                "0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z",
        ),
        FreshIconType.MAP to vector(
            "map",
            "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 " +
                "0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 " +
                "0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z",
        ),
        FreshIconType.ROUTE to vector(
            "route",
            "M18,4c-1.3,0 -2.4,0.84 -2.82,2L11,6c-2.21,0 -4,1.79 -4,4s1.79,4 4,4h2c1.1,0 2,0.9 " +
                "2,2s-0.9,2 -2,2H8.82C8.4,16.84 7.3,16 6,16c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c1.3,0 " +
                "2.4,-0.84 2.82,-2H13c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4h-2c-1.1,0 -2,-0.9 " +
                "-2,-2s0.9,-2 2,-2h4.18C15.6,8.16 16.7,9 18,9c1.66,0 3,-1.34 3,-3S19.66,4 18,4z" +
                "M18,7c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM6,20c-0.55,0 " +
                "-1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z",
        ),
        FreshIconType.TASK to vector(
            "task",
            "M19,3h-4.18C14.4,1.84 13.3,1 12,1c-1.3,0 -2.4,0.84 -2.82,2L5,3c-1.1,0 -2,0.9 " +
                "-2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM12,3c0.55,0 " +
                "1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM10,17l-4,-4 1.41,-1.41L10,14.17" +
                "l6.59,-6.59L18,9l-8,8z",
        ),
        FreshIconType.PICKUP to vector(
            "pickup",
            "M20.55,5.22l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.15,0.55L3.46,5.22" +
                "C3.17,5.57 3,6.01 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.49 -0.17,-0.93 " +
                "-0.45,-1.28zM12,9.5l5.5,5.5H14v2h-4v-2H6.5L12,9.5zM5.12,5l0.82,-1h12l0.93,1H5.12z",
        ),
        FreshIconType.DELIVERY to vector(
            "delivery",
            "M20,8h-3V4H3c-1.1,0 -2,0.9 -2,2v11h2c0,1.66 1.34,3 3,3s3,-1.34 3,-3h6c0,1.66 1.34,3 " +
                "3,3s3,-1.34 3,-3h2v-5l-3,-4zM6,18.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 " +
                "1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM19.5,9.5l1.96,2.5H17V9.5h2.5zM18,18.5c-0.83,0 " +
                "-1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        ),
        FreshIconType.MESSAGE to vector(
            "message",
            "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
                "M6,9h12v2H6V9zM14,14H6v-2h8v2zM18,8H6V6h12v2z",
        ),
        FreshIconType.CHAT to vector(
            "chat",
            "M15,4v7H5.17l-0.59,0.59 -0.58,0.58V4h11m1,-2H3c-0.55,0 -1,0.45 -1,1v14l4,-4h10c0.55,0 " +
                "1,-0.45 1,-1V3c0,-0.55 -0.45,-1 -1,-1zM21,6h-2v9H6v2c0,0.55 0.45,1 1,1h11l4,4V7c0,-0.55 " +
                "-0.45,-1 -1,-1z",
        ),
        FreshIconType.PROFILE to vector(
            "profile",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 " +
                "-8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
        ),
        FreshIconType.STATS to vector(
            "stats",
            "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z",
        ),
        FreshIconType.SETTINGS to vector(
            "settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
                "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22" +
                "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 " +
                "-0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 " +
                "7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 " +
                "2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58" +
                "c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96" +
                "c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 " +
                "0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 " +
                "0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6" +
                "c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        ),
        FreshIconType.ABOUT to vector(
            "about",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6z" +
                "M13,9h-2L11,7h2v2z",
        ),
        FreshIconType.BATTERY to vector(
            "battery",
            "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 " +
                "1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z",
        ),
        FreshIconType.LOCK to vector(
            "lock",
            "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 " +
                "2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 " +
                "2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 " +
                "3.1,3.1v2z",
        ),
        FreshIconType.LOCATION to vector(
            "location",
            "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5" +
                "c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z",
        ),
        FreshIconType.STORE to vector(
            "store",
            "M20,4H4v2h16V4zM21,14v-2l-1,-5H4l-1,5v2h1v6h10v-6h4v6h2v-6h1zM12,18H6v-4h6v4z",
        ),
        FreshIconType.RIDER to vector(
            "rider",
            "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z",
        ),
        FreshIconType.NAVIGATE to vector(
            "navigate",
            "M21,3L3,10.53v0.98l6.84,2.65L12.48,21h0.98L21,3z",
        ),
        FreshIconType.PACKAGE to vector(
            "package",
            "M20,2H4C3,2 2,2.9 2,4v3.01C2,7.73 2.43,8.35 3,8.7V20c0,1.1 1.1,2 2,2h14c0.9,0 2,-0.9 " +
                "2,-2V8.7c0.57,-0.35 1,-0.97 1,-1.69V4c0,-1.1 -1,-2 -2,-2zM15,14H9v-2h6v2zM20,7H4V4h16v3z",
        ),
        FreshIconType.CLOCK to vector(
            "clock",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 " +
                "0.75,-1.23 -4.5,-2.67z",
        ),
        FreshIconType.TIMER to vector(
            "timer",
            "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 " +
                "-1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 " +
                "9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 " +
                "7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        ),
        FreshIconType.REFRESH to vector(
            "refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 " +
                "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 " +
                "3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        ),
        FreshIconType.HOME to vector(
            "home",
            "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z",
        ),
        FreshIconType.MENU to vector(
            "menu",
            "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z",
        ),
        FreshIconType.BELL to vector(
            "bell",
            "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 " +
                "-4.5,-6.32L13.5,4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 " +
                "6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
        ),
        FreshIconType.LOCATE to vector(
            "locate",
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11" +
                "c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06" +
                "c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2" +
                "h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        ),
        FreshIconType.FEEDBACK to vector(
            "feedback",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 " +
                "0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        ),
        FreshIconType.SORT to vector(
            "sort",
            "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z",
        ),
        FreshIconType.SHIELD to vector(
            "shield",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4zM10,17l-4,-4 " +
                "1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z",
        ),
        FreshIconType.BOLT to vector(
            "bolt",
            "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 " +
                "0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15" +
                "C12.96,17.55 11,21 11,21z",
        ),
        FreshIconType.WALLET to vector(
            "wallet",
            "M21,18v1c0,1.1 -0.9,2 -2,2H5c-1.11,0 -2,-0.9 -2,-2V5c0,-1.1 0.89,-2 2,-2h14c1.1,0 " +
                "2,0.9 2,2v1h-9c-1.11,0 -2,0.9 -2,2v8c0,1.1 0.89,2 2,2h9zM12,16h10V8H12v8z" +
                "M16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        ),
        FreshIconType.HEADSET to vector(
            "headset",
            "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 " +
                "7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z",
        ),
        FreshIconType.ADD to vector(
            "add",
            "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        ),
        FreshIconType.WALK to vector(
            "walk",
            "M13.5,5.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM9.8,8.9L7,23h2.1l1.8,-8 " +
                "2.1,2v6h2v-7.5l-2.1,-2 0.6,-3C14.8,12 16.8,13 19,13v-2c-1.9,0 -3.5,-1 -4.3,-2.4l-1,-1.6" +
                "c-0.4,-0.6 -1,-1 -1.7,-1 -0.3,0 -0.5,0.1 -0.8,0.1L6,8.3V13h2V9.6l1.8,-0.7z",
        ),
        FreshIconType.EBIKE to vector(
            "ebike",
            "M19,7c0,-1.1 -0.9,-2 -2,-2h-3v2h3v2.65L13.52,14H10V9H6c-2.21,0 -4,1.79 -4,4v3h2c0,1.66 " +
                "1.34,3 3,3s3,-1.34 3,-3h4.48L19,10.35V7zM7,17c-0.55,0 -1,-0.45 -1,-1h2c0,0.55 -0.45,1 -1,1z",
            "M5,6h5v2H5zM19,13c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3zM19,17" +
                "c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z",
        ),
        FreshIconType.CAR to vector(
            "car",
            "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 " +
                "0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 " +
                "1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 " +
                "1.5,1.5 -0.67,1.5 -1.5,1.5zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 " +
                "1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11L5,11z",
        ),
    )
}

@Preview(name = "图标总览", showBackground = true)
@Composable
private fun FreshIconPreview() {
    RiderTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(FreshSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                FreshIconType.entries.chunked(8).forEach { icons ->
                    Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm)) {
                        icons.forEach { FreshIcon(it, contentDescription = it.name, size = 22.dp) }
                    }
                }
            }
        }
    }
}
