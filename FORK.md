# OpenWispr "volkey" fork

Personal fork of [RohitAg13/openWispr](https://github.com/RohitAg13/openWispr) (MIT). Same app,
same on-device STT + polish pipeline — **no floating bubble, and dictation starts from the volume
keys.**

## What changed vs upstream

| | Upstream | This fork |
|---|---|---|
| Trigger | Floating bubble (overlay + foreground service) | **Hold either volume key** ~0.4 s while a text field is focused / the keyboard is up → dictation starts immediately. Releasing the key ends the take and sends it. |
| Secondary trigger | — | **Quick Settings tile** ("OpenWispr") — one tap, take ends on the stop button or the VAD auto-stop. |
| Idle UI | Always-on bubble (field-gated) | Nothing. The sheet only exists while a take is running, then inserts and closes itself. |
| Bubble | On after onboarding | Off. Still available from **Settings → floating bubble** if you want it back. |

Files touched:

- `res/xml/accessibility_service_config.xml` — `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents`
- `OpenWisprAccessibilityService.kt` — `onKeyEvent` hold-to-talk trigger, volume-tap replay, arming gate
- `RewriteActivity.kt` — `dictateIntent(...)`, shared stop hook so a key release ends the take
- `DictateTileService.kt` (new) + manifest entry — Quick Settings tile
- `OnboardingActivity.kt` — no longer starts the bubble at the end of setup
- `app/build.gradle.kts` — `versionName = 1.4.0-volkey` (`versionCode` unchanged on purpose)

## Behaviour notes

- **Volume keys keep working normally** for a tap: the press is consumed while deciding whether it
  is a hold, and a short tap is replayed to the system (`AudioManager.adjustStreamVolume`, music
  stream, system UI). Holding a volume key no longer ramps the volume while a text field is focused —
  that is the trade for the trigger.
- **The trigger only arms where it makes sense**: a focused editable field, or a keyboard on screen.
  Everywhere else it does not touch the key events.
- A hold released in under ~350 ms is discarded rather than transcribed (nothing worth sending).
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
