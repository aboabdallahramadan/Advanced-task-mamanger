# TASK-GAPB: Auth Screens Midnight Calm Restyle — Report

**Status:** COMPLETE  
**Date:** 2026-06-25  
**Files changed:**
- `android/app/src/main/java/net/qmindtech/tmap/ui/auth/LoginScreen.kt`
- `android/app/src/main/java/net/qmindtech/tmap/ui/auth/RegisterScreen.kt`
- `android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthBanner.kt`

---

## Behavior Checklist vs Midnight Calm

### Background
- [x] `TmapBackground` composable used — renders `bgTop→bgBottom` vertical gradient on both screens
- [x] No raw `MaterialTheme.colorScheme.background` or `Surface(color=...)` for the screen root

### Typography
- [x] "TMap" amber wordmark at top using `type.display` + `colors.accent`
- [x] Heading ("Welcome back" / "Create your account") uses `type.heading` + `colors.textPrimary`
- [x] Subtitle uses `type.body` + `colors.textSecondary`
- [x] Password hint / meta text uses `type.meta` + `colors.textTertiary` (or `colors.danger` when error)
- [x] No `MaterialTheme.typography.*` for content text

### Card / Surface
- [x] Card replaced by `Box` with `colors.surface` fill + `RoundedCornerShape(shapes.card)` (18 dp)
- [x] No raw `MaterialTheme.colorScheme.surface` or M3 `Card`

### OutlinedTextField
- [x] `OutlinedTextFieldDefaults.colors(...)` set for both screens with token colors:
  - focused border → `colors.accent`
  - unfocused border → `colors.borderStrong`
  - label focused → `colors.accent`
  - label unfocused → `colors.textTertiary`
  - container → `colors.surfaceInset`
  - text → `colors.textPrimary`
  - cursor → `colors.accent`
  - error border/label → `colors.danger` (Register only)
- [x] `textStyle = type.body` applied
- [x] No default M3 purple focus ring

### Primary Button
- [x] `PrimaryButton` composable from `ui/components/Buttons.kt` used (amber gradient, `onAccent` text, `shapes.button` radius)
- [x] `enabled = state.canSubmit` preserved
- [x] No raw M3 `Button`

### Submitting State
- [x] When `state.submitting == true`, `PrimaryButton` is replaced by a centered `CircularProgressIndicator` tinted `colors.accent`
- [x] Switch link disabled + faded to `colors.textTertiary` when `submitting`

### Switch Link
- [x] Replaced M3 `TextButton` with `Text` + `.clickable(role = Role.Button)`
- [x] Color: `colors.accent` (enabled) / `colors.textTertiary` (disabled)
- [x] Full-width, centered, padded touch target

### AuthBanner
- [x] No `MaterialTheme.*` references
- [x] Error state: `colors.danger` at 15% alpha container, `colors.danger` text
- [x] Warning/network state: `colors.surfaceRaised` container, `colors.textSecondary` text
- [x] Shape: `RoundedCornerShape(shapes.well)` (12 dp)
- [x] Text style: `type.meta`
- [x] `liveRegion` semantics preserved (Assertive for errors, Polite for network)

### Logic / Signatures (UNCHANGED)
- [x] `LoginScreen` signature: `(state, onEmailChange, onPasswordChange, onSubmit, onSwitchToRegister)` — identical
- [x] `RegisterScreen` signature: `(state, onEmailChange, onPasswordChange, onSubmit, onSwitchToLogin)` — identical
- [x] `AuthUiState` — not touched
- [x] `AuthViewModel` — not touched
- [x] `state.canSubmit` gate preserved on PrimaryButton
- [x] `state.submitting` gate on all interactive controls preserved
- [x] `state.networkError` / `state.errorMessage` banner logic preserved
- [x] `state.passwordTooShort` isError on password field + hint text preserved (Register)
- [x] `AuthUiState.MIN_PASSWORD` constant used in hint preserved

### Previews
- [x] Both `@Preview` functions still wrap in `TmapTheme {}` — no change needed

### Constraints Compliance
- [x] No hardcoded hex color values
- [x] No `MaterialTheme.colorScheme.*` for content colors
- [x] No `MaterialTheme.typography.*` for content text
- [x] No old `Surface*/Accent*` palette
- [x] Dark-only (app is always dark; `TmapTheme` bridges to M3 dark scheme)
- [x] RTL: layout uses `fillMaxWidth` + no hardcoded `start`/`end` padding asymmetries
- [x] A11y: switch link uses `role = Role.Button` for clickable text; `CircularProgressIndicator` is standard

---

## Build Gate
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL in 22s**
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 44s** (all AuthViewModel tests green)
