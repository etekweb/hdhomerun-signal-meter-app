package net.etekweb.hdhrmeter.data

enum class HardwareDecodeMode {
    Always,
    HdOnly,
    Never;

    internal fun requestsHardware(isHd: Boolean?): Boolean = when (this) {
        Always -> true
        HdOnly -> isHd == true
        Never -> false
    }
}
