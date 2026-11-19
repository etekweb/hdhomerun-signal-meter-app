package net.etekweb.hdhrmeter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingNoticeTest {
    @Test
    fun noticeOnlyAppearsOnTheChannelAndAiringThatWasScheduled() {
        val start = 1_800_000_000_000L
        val notice = RecordingNotice("7.1", start, "The DVR refresh could not be confirmed.")
        val schedule = ScheduleState(notice = notice)

        assertEquals(notice.message, schedule.noticeFor("7.1", start))
        assertNull(schedule.noticeFor("7.2", start))
        assertNull(schedule.noticeFor("7.1", start + 1_800_000L))
        assertNull(schedule.noticeFor("7.1", null))
    }
}
