# OpenWispr "volkey" fork

Personal fork of [RohitAg13/openWispr](https://github.com/RohitAg13/openWispr) (MIT). Same app,
same on-device STT + polish pipeline — **no floating bubble, and dictation starts from the volume
keys.**

## What changed vs upstream

| | Upstream | This fork |
|---|---|---|
| Trigger | Floating bubble (overlay + foreground service) | **Double-press either volume key** while a text field is focused / the keyboard is up → dictation starts immediately. A **single press ends the take**; so does the VAD auto-stop or the sheet's own button. |
| Secondary trigger | — | **Quick Settings tile** ("OpenWispr") — one tap, take ends on the stop button or the VAD auto-stop. |
| Idle UI | Always-on bubble (field-gated) | Nothing. The sheet only exists while a take is running, then inserts and closes itself. |
| Bubble | On after onboarding | Off. Still available from **Settings → floating bubble** if you want it back. |

Files touched:

- `res/xml/accessibility_service_config.xml` — `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents`
- `OpenWisprAccessibilityService.kt` — `onKeyEvent` hold-to-talk trigger, volume-tap replay, arming gate
- `RewriteActivity.kt` — `dictateIntent(...)`, the stop hook behind the press-to-stop, and
  `insertNow(...)` replacing the review stage so the result inserts as soon as it is ready
- `DictateTileService.kt` (new) + manifest entry — Quick Settings tile
- `OnboardingActivity.kt` — no longer starts the bubble at the end of setup
- `app/build.gradle.kts` — `versionName = 1.4.0-volkey` (`versionCode` unchanged on purpose)

## Behaviour notes

- **Single taps and held keys are untouched.** The first press of a pair is passed straight to the
  system, so a tap changes the volume and holding ramps it exactly as before — the trigger never
  swallows them, it only watches. The second press of a pair is consumed, so a triggered pair costs
  one volume step instead of two.
- **The double press has to be quick**: two full press/release cycles with the second press starting
  within **300 ms** of the first release. Two deliberate volume steps are slower than that, so
  turning the volume down two notches does not start a dictation. A press held longer than 200 ms
  was a ramp, not a tap, and never pairs with the press after it.
- **One press while it is listening ends the take** — double press to start, press to stop, no screen
  required. The VAD auto-stop ends it when you pause too, and the sheet's own button is there if you
  would rather tap that.
- **The trigger only arms where it makes sense**: a focused editable field, a keyboard on screen, or
  a take already running. Everywhere else the volume keys are not even looked at.
- **No review step.** When transcription + polish finish, the text is inserted at the cursor and the
  sheet closes — no countdown, no edit screen, no "accept" tap. The reasons this is safe: the result
  is still written to Home's history next to the recording (so a bad take is fixable afterwards), and
  the delivery is haptically confirmed when the text lands in the field.
- If the accessibility service is off, there is no trigger and no insertion — it is required for both.

## Install

1. **Uninstall any other OpenWispr first.** This APK is signed with its own key, so it cannot be
   installed over the upstream/Play build ("App not installed" otherwise). App data goes with the
   uninstall, so the speech + polish models download again on first use.
2. Install `OpenWispr-volkey.apk` from the release (arm64 devices, Android 7.0+).
3. Enable **Settings → Accessibility → OpenWispr** (required: it inserts the finished text and owns
   the volume-key trigger). Toggle it off/on if the trigger does not respond right after install —
   the key-filter capability is read when the service is enabled.
4. Optional: add the **OpenWispr** Quick Settings tile.

## Build

`.github/workflows/apk.yml` builds a signed release APK on every push touching `android/**` and
republishes it to the rolling `volkey-latest` release. The keystore lives in the repo's Actions
secrets (`OPENWISPR_KEYSTORE_BASE64`, `OPENWISPR_KEYSTORE_PASSWORD`, `OPENWISPR_KEY_ALIAS`,
`OPENWISPR_KEY_PASSWORD`), so rebuilds stay upgradeable in place.

Upstream's `ci.yml` still runs the Android unit tests + debug build on push; its `release.yml` is
untouched and still needs upstream's Play secrets to do anything.
