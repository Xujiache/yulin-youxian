package com.yulin.rider.feature.map

import com.yulin.rider.core.model.GeoPoint

/**
 * 解码服务端下发的规划折线。
 *
 * 服务端 GeoUtils.encodePolyline 用的是标准 Google 编码折线(精度 1e5、差分 + 5 位分组),
 * 这里是它的逆运算。无高德 Key 时服务端下发的是直线段串联,格式完全一致,照样能画。
 */
fun decodePolyline(encoded: String?): List<GeoPoint> {
    if (encoded.isNullOrBlank()) return emptyList()
    val points = mutableListOf<GeoPoint>()
    var index = 0
    var lat = 0L
    var lng = 0L

    try {
        while (index < encoded.length) {
            lat += readSigned(encoded, index) { index = it }
            lng += readSigned(encoded, index) { index = it }
            points += GeoPoint(lat = lat / 1e5, lng = lng / 1e5)
        }
    } catch (e: IndexOutOfBoundsException) {
        // 折线被截断时保留已解出的部分,画一半也比画不出强
        return points
    }
    return points
}

private inline fun readSigned(encoded: String, start: Int, onConsumed: (Int) -> Unit): Long {
    var index = start
    var shift = 0
    var result = 0L
    while (true) {
        val b = encoded[index++].code - 63
        result = result or ((b and 0x1f).toLong() shl shift)
        shift += 5
        if (b < 0x20) break
    }
    onConsumed(index)
    return if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
}
