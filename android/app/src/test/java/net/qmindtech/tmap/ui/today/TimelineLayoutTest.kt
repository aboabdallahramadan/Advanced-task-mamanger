package net.qmindtech.tmap.ui.today

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TimelineLayoutTest {
    @Test fun blockOffset_is_zero_at_rail_start() {
        assertEquals(0.dp, blockOffsetDp(LocalTime.of(9, 0)))
    }

    @Test fun blockOffset_scales_72dp_per_hour() {
        assertEquals(36.dp, blockOffsetDp(LocalTime.of(9, 30)))
        assertEquals(72.dp, blockOffsetDp(LocalTime.of(10, 0)))
        assertEquals(360.dp, blockOffsetDp(LocalTime.of(14, 0)))
    }

    @Test fun blockOffset_before_rail_start_clamps_to_zero() {
        assertEquals(0.dp, blockOffsetDp(LocalTime.of(7, 0)))
    }

    @Test fun blockHeight_scales_with_duration() {
        assertEquals(72.dp, blockHeightDp(60))
        assertEquals(108.dp, blockHeightDp(90))
    }

    @Test fun blockHeight_null_duration_defaults_to_one_hour() {
        assertEquals(72.dp, blockHeightDp(null))
    }

    @Test fun blockHeight_short_block_clamped_to_minimum() {
        assertEquals(36.dp, blockHeightDp(15)) // raw 18dp → clamped up to 36dp
    }

    @Test fun railHeight_spans_the_full_window() {
        assertEquals((22 - 9) * 72, TimelineDefaults.railHeight.value.toInt())
    }

    // P7.2 — nowLineOffsetDp

    @Test fun nowLine_at_rail_start_is_zero() {
        assertEquals(0.dp, nowLineOffsetDp(LocalTime.of(9, 0)))
    }

    @Test fun nowLine_scales_with_minutes() {
        assertEquals(64.8f, nowLineOffsetDp(LocalTime.of(9, 54)).value, 0.01f) // 54/60*72
    }

    @Test fun nowLine_before_window_pins_to_top() {
        assertEquals(0.dp, nowLineOffsetDp(LocalTime.of(8, 0)))
    }

    @Test fun nowLine_after_window_pins_to_bottom() {
        assertEquals(TimelineDefaults.railHeight, nowLineOffsetDp(LocalTime.of(23, 0)))
    }

    // P7.2 — dropOffsetToTime

    @Test fun dropOffset_maps_back_to_rail_start() {
        assertEquals(LocalTime.of(9, 0), dropOffsetToTime(0.dp))
    }

    @Test fun dropOffset_snaps_to_nearest_quarter_hour() {
        assertEquals(LocalTime.of(9, 30), dropOffsetToTime(36.dp))  // exactly 30 min
        assertEquals(LocalTime.of(9, 30), dropOffsetToTime(40.dp))  // 33.3 min → 30
        assertEquals(LocalTime.of(9, 45), dropOffsetToTime(46.dp))  // 38.3 min → 45
    }

    @Test fun dropOffset_clamps_below_and_above() {
        assertEquals(LocalTime.of(9, 0), dropOffsetToTime((-50).dp))
        assertEquals(LocalTime.of(22, 0), dropOffsetToTime(99999.dp))
    }
}
