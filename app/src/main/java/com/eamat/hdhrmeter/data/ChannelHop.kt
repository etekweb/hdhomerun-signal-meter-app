package net.etekweb.hdhrmeter.data

/**
 * The channel a tap of channel up or down would land on.
 * [pending] is the preview already shown, so repeated taps walk the lineup
 * from there. Copy-protected channels are skipped. Returns null when the tap
 * lands on the channel that is already playing, or when nothing can be played.
 */
fun previewHop(
    channels: List<Channel>,
    playing: Channel,
    pending: Channel?,
    delta: Int,
): Channel? {
    val list = channels.filter { !it.drm }
    if (list.isEmpty()) return null
    val from = pending ?: playing
    val index = list.indexOfFirst {
        it.guideNumber == from.guideNumber && it.streamUrl == from.streamUrl
    }
    val next = list[if (index < 0) 0 else Math.floorMod(index + delta, list.size)]
    return if (next.guideNumber == playing.guideNumber && next.streamUrl == playing.streamUrl) {
        null
    } else {
        next
    }
}
