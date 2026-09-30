# OpenWispr "volkey" fork

Personal fork of [RohitAg13/openWispr](https://github.com/RohitAg13/openWispr) (MIT). Same app,
same on-device STT + polish pipeline — **no floating bubble, and dictation starts from the volume
keys.**

## What changed vs upstream

| | Upstream | This fork |
|---|---|---|
| Trigger | Floating bubble (overlay + foreground service) | **Volume-up and volume-down pressed together** → dictation starts immediately, anywhere. **A single press while it is listening ends the take**; so does the VAD auto-stop or the sheet's own button. |
| Secondary trigger | — | **Quick Settings tile** ("OpenWispr") — one tap, take ends on the stop button or the VAD auto-stop. |
| Cloud STT | Groq / OpenAI / Custom | Same, **plus a first-class Deepgram engine** — `POST /v1/listen`, `Token` auth, raw WAV, and Deepgram's own options in Settings (model, language, region endpoint, smart format, punctuation, numerals, dictation mode, paragraphs, measurements, diarize, filler words, profanity filter, redact, dictionary→key terms, MIP opt-out). |
| Idle UI | Always-on bubble (field-gated) | Nothing. The sheet only exists while a take is running, then inserts and closes itself. |
| Bubble | On after onboarding | Off. Still available from **Settings → floating bubble** if you want it back. |

Files touched:

- `res/xml/accessibility_service_config.xml` — `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents`
- `OpenWisprAccessibilityService.kt` — `onKeyEvent` hold-to-talk trigger, volume-tap replay, arming gate
- `RewriteActivity.kt` — `dictateIntent(...)`, the stop hook behind the press-to-stop, and
  `insertNow(...)` replacing the review stage so the result inserts as soon as it is ready
- `DictateTileService.kt` (new) + manifest entry — Quick Settings tile
- `OnboardingActivity.kt` — no longer starts the bubble at the end of setup
- `SttEngine.kt` / `Settings.kt` / `Defaults.kt` / `SettingsActivity.kt` — the Deepgram engine: a
  second request shape (Deepgram is not OpenAI-compatible) and the ~15 options that shape a Deepgram
  transcript. Personal-dictionary terms are sent as Deepgram key terms (Nova-3) or keywords (older
  models), and `punctuate=true` is sent with `dictation=true` because Deepgram requires the pair.
- `app/build.gradle.kts` — `versionName = 1.4.0-volkey` (`versionCode` unchanged on purpose)

## Behaviour notes

- **Nothing is inferred from timing.** The grip is a physical pair of keys: both are down at the same
  moment, or it did not happen. No double-press window, no tap-vs-hold classification.
- **A lone volume key is never consumed.** Taps change the volume and a held key ramps it exactly as
  before. All that is consumed is the *second* key of a grip (and the press that stops a take), and
  each consumed key stays hidden for its whole press so the system never sees half a key pair.
- **The grip puts its own volume step back.** The first key is passed to the system, so it moves the
  volume one step; that step is quietly undone when the grip fires (skipped when the volume is
  already at its limit, where the press did nothing to undo).
- **Holding one key to ramp and then catching the other is not a trigger** — the held key is marked as
  a ramp, so the pair is read as a volume correction, as it should be.
- **One press while it is listening ends the take.** The VAD auto-stop ends it when you pause too, and
  the sheet's own button is there if you would rather tap that. Holding the grip does not stop it.
- **No context gate**: the grip works on any screen, whether or not a text field is focused.
- **No review step.** When transcription + polish finish, the text is inserted at the cursor and the
  sheet closes — no countdown, no edit screen, no "accept" tap. The reasons this is safe: the result
  is still written to Home's history next to the recording (so a bad take is fixable afterwards), and
  the delivery is haptically confirmed when the text lands in the field.
- If the accessibility service is off, there is no trigger and no insertion — it is required for both.
- One device-level conflict worth knowing: holding **both** volume keys for ~3 seconds is Android's
  own accessibility shortcut. Grip-and-hold may therefore pop the system's shortcut dialog on some
  phones; turn that off in Settings → Accessibility → Accessibility shortcut, where OpenWispr does not
  need it.

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
