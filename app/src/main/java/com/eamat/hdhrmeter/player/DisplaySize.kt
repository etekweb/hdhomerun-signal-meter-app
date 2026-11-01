package net.etekweb.hdhrmeter.player

import org.videolan.libvlc.interfaces.IMedia

/**
 * Square-pixel size of the picture, in the orientation it is shown.
 *
 * [width] and [height] are stored samples. [sarNum] and [sarDen] are the sample
 * aspect ratio. A null result means the size is not known yet.
 */
internal fun displayResolution(
    width: Int,
    height: Int,
    sarNum: Int,
    sarDen: Int,
    orientation: Int = IMedia.VideoTrack.Orientation.TopLeft,
): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    var displayWidth = width.toLong()
    var displayHeight = height.toLong()
    var sampleNum = sarNum.toLong()
    var sampleDen = sarDen.toLong()
    // MPEG-2 has no crop rectangle. 1080-line frames are stored as 1088 so the
    // height is a multiple of 16, and some encoders put 1088 in the sequence header.
    // VLC's HDTV fix shortens that to 1080 and, when the sample aspect was built
    // from the padded height, scales it by 135/136 so the picture keeps its shape.
    if (displayHeight == 1088L) {
        displayHeight = 1080L
        if (sampleNum > 0 && sampleDen > 0 && sampleNum % 136L == 0L) {
            sampleNum *= 135L
            sampleDen *= 136L
        }
    }
    if (sampleNum > 0 && sampleDen > 0 && sampleNum != sampleDen) {
        displayWidth = (displayWidth * sampleNum + sampleDen / 2) / sampleDen
    }
    if (orientationSwapsAxes(orientation)) {
        val swap = displayWidth
        displayWidth = displayHeight
        displayHeight = swap
    }
    if (displayWidth !in 1..Int.MAX_VALUE.toLong() || displayHeight !in 1..Int.MAX_VALUE.toLong()) {
        return null
    }
    return displayWidth.toInt() to displayHeight.toInt()
}

/**
 * True when [a] and [b] describe one picture edge. Decoders pad the stored size
 * up to the next 16, 32, or 64 pixels, which adds at most 63 pixels.
 */
internal fun fitsCodingAlignment(a: Int, b: Int): Boolean {
    if (a <= 0 || b <= 0) return false
    if (a == b) return true
    val larger = maxOf(a, b)
    val pad = larger - minOf(a, b)
    return pad <= 63 && larger % 16 == 0
}

private fun orientationSwapsAxes(orientation: Int): Boolean {
    return orientation == IMedia.VideoTrack.Orientation.LeftTop ||
        orientation == IMedia.VideoTrack.Orientation.LeftBottom ||
        orientation == IMedia.VideoTrack.Orientation.RightTop ||
        orientation == IMedia.VideoTrack.Orientation.RightBottom
}
