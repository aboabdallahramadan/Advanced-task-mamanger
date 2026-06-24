# Task P5.4 Report — PlanningViewModel

## Status: DONE

## TDD Cycle

### RED
Created `PlanningViewModelTest.kt` first. Running the test immediately produced:
```
e: Unresolved reference 'PlanningViewModel'
```
Compile failed as expected — no ViewModel existed yet.

### GREEN
Created `PlanningViewModel.kt`. Tests compiled and all 3 passed on first run:
- `reflect_splits_yesterday_done_and_undone`
- `pickToday_lists_carryover_and_inbox_and_toggleAdd_updates_capacity`
- `next_and_back_move_the_step_clamped`

Full suite: **483 tests, 0 failures**.

## Files Created

- `android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningViewModelTest.kt` — 3 tests covering reflect split, pick/toggleAdd capacity, step navigation
- `android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningViewModel.kt` — HiltViewModel with 5-source combine, carry-over/inbox projection, toggleAdd, next/back

## Design Notes

The ViewModel uses a 5-argument `combine` (yesterday tasks, inbox tasks, projects, settings, local ritual state) to produce `PlanningUiState` as a `StateFlow`. Local ritual state (step, picked IDs, committed flag) is held in `MutableStateFlow` fields and combined via a nested `combine(step, picked, committed)` triple. The `toggleAdd` function uses list add/remove to maintain ordered pick semantics. `capacityOf()` and `workdayMinutes()` from `PlanningCapacity.kt` are reused as-is.
