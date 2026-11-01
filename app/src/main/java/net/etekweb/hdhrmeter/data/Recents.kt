package net.etekweb.hdhrmeter.data

/** Newest viewed guide number first. Older entries fall off the end. */
const val MAX_RECENT_CHANNELS = 100

/**
 * Returns [recent] with [guideNumber] at the front.
 * Watching the same channel again does not add a second entry.
 * When it is already first and the list is within [limit], the same list is returned.
 */
fun rememberViewedChannel(
    recent: List<String>,
    guideNumber: String,
    limit: Int = MAX_RECENT_CHANNELS,
): List<String> {
    if (guideNumber.isEmpty()) return recent
    if (recent.firstOrNull() == guideNumber) {
        return if (recent.size <= limit) recent else recent.take(limit)
    }
    return (listOf(guideNumber) + recent.filter { it != guideNumber }).take(limit)
}

/** Lineup rows the viewer has watched, newest first. Unwatched rows are left out. */
fun channelsInViewOrder(channels: List<Channel>, recentGuideNumbers: List<String>): List<Channel> {
    if (recentGuideNumbers.isEmpty() || channels.isEmpty()) return emptyList()
    val rank = HashMap<String, Int>(recentGuideNumbers.size)
    recentGuideNumbers.forEachIndexed { index, number ->
        rank.putIfAbsent(number, index)
    }
    return channels
        .asSequence()
        .mapNotNull { channel -> rank[channel.guideNumber]?.let { channel to it } }
        .sortedBy { it.second }
        .map { it.first }
        .toList()
}
