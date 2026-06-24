package net.qmindtech.tmap.ui.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningStepTest {
    @Test fun steps_are_in_ritual_order() {
        assertEquals(
            listOf("Reflect", "TriageInbox", "PickToday", "Timebox"),
            PlanningStep.entries.map { it.name },
        )
        assertEquals(4, PlanningStep.STEP_COUNT)
    }

    @Test fun next_advances_and_clamps_at_last() {
        assertEquals(PlanningStep.TriageInbox, PlanningStep.Reflect.next())
        assertEquals(PlanningStep.PickToday, PlanningStep.TriageInbox.next())
        assertEquals(PlanningStep.Timebox, PlanningStep.PickToday.next())
        assertEquals(PlanningStep.Timebox, PlanningStep.Timebox.next()) // clamp
    }

    @Test fun back_retreats_and_clamps_at_first() {
        assertEquals(PlanningStep.PickToday, PlanningStep.Timebox.back())
        assertEquals(PlanningStep.Reflect, PlanningStep.TriageInbox.back())
        assertEquals(PlanningStep.Reflect, PlanningStep.Reflect.back())  // clamp
    }

    @Test fun eyebrow_matches_the_mockup_format() {
        assertEquals("STEP 3 OF 4 · PICK YOUR DAY", PlanningStep.PickToday.eyebrow)
        assertEquals("STEP 1 OF 4 · REFLECT", PlanningStep.Reflect.eyebrow)
    }

    @Test fun headings_present_per_step() {
        assertEquals("What deserves your time today?", PlanningStep.PickToday.heading)
    }

    @Test fun uiState_advance_retreat_and_flags() {
        val s0 = PlanningUiState(step = PlanningStep.Reflect)
        assertEquals(true, s0.isFirstStep)
        val s1 = s0.advance()
        assertEquals(PlanningStep.TriageInbox, s1.step)
        val last = PlanningUiState(step = PlanningStep.Timebox)
        assertEquals(true, last.isLastStep)
        assertEquals(PlanningStep.Timebox, last.advance().step) // clamp
        assertEquals(PlanningStep.PickToday, last.retreat().step)
    }

    @Test fun capacityFraction_derives_from_planned_over_workday() {
        val s = PlanningUiState(plannedMinutes = 180, workdayMinutes = 360)
        assertEquals(0.5f, s.capacityFraction, 0.0001f)
    }
}
