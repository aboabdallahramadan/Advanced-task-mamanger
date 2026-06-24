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
}
