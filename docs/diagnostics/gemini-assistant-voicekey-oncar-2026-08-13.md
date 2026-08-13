# On-car procedure — Gemini as assistant + mic-button → Gemini (prep 2026-08-13)

> Off-car prep so the next on-car session is just execution. Vehicle IP redacted → set `VEH=<vehicle-ip>`.
> Two independent paths; Path A is the "enable Gemini as the car's assistant" the community got working.

```bash
VEH=<vehicle-ip>
adb connect "$VEH"
GSA="com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
REC="com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
```

## What tonight's probing established
- The Gemini overlay in the community photo = the **Google app VoiceInteractionService session** (`GsaVoiceInteractionService`, "robin" car voice surface). It has a text box "Hỏi Gemini" + a mic.
- On this car **no assistant was set** (`settings get secure voice_interaction_service` = empty) → that's why ACTION_ASSIST hit a chooser, "OK Google" had nothing to trigger, and Gemini said "voice not supported". **Setting Google/Gemini as the assistant is the enable step** (== Settings → Apps → Default apps → Digital assistant app → Google/Gemini, which BYD hides — so do it via adb).
- Mic/voice button surfaces on input device `simulate-keys` (event7) as **gamepad pulse codes**: short-tap = `BTN_THUMB2`; **long-press = `BTN_BASE2`/`BTN_TL2` → Android keycode `328`** (learned via ClusterNav's learn-key → so `onKeyEvent` DOES receive it). Short and long are **different codes** → map long→Gemini, leave short = car assistant.
- Launching `com.google.android.apps.bard` **directly** opens Gemini's robin car voice surface (works). The generic `ACTION_ASSIST` intent opened a chooser → Bluetooth (the bug the owner hit). **1.16→1.17 fix:** ClusterNav's "Google / Gemini" voice-key target now launches Gemini directly.
- "Voice not supported on this device" in Gemini is almost certainly the **always-on "Hey Google" hotword** (needs device certification the head unit lacks), NOT the tap-mic in the overlay — tap-mic likely works.

---

## Path A — enable Gemini as the system assistant (device config, no app needed)
```bash
# was empty; set Google/Gemini as assistant + recognizer
adb -s "$VEH" shell "settings put secure voice_interaction_service '$GSA'"
adb -s "$VEH" shell "settings put secure assistant '$GSA'"
adb -s "$VEH" shell "settings put secure voice_recognition_service '$REC'"
# verify
adb -s "$VEH" shell "settings get secure voice_interaction_service; settings get secure assistant"
```
Then **reboot the head unit** (physical power button) so the assist framework re-binds.

**Test after reboot:**
1. Open Gemini once, sign in / accept terms if prompted.
2. Invoke the assistant: long-press Home, or the assist gesture, or the mapped mic button (Path B). → does the **Gemini overlay** ("Hỏi Gemini") appear?
3. Tap the **mic** in the overlay and speak → does Gemini answer? (This is the realistic "voice" path.)
4. Try saying **"OK Google"** (hotword) — may not work on this unit; don't rely on it.

**State now:** set tonight but not yet verified (adb went offline right after). Re-run the verify line on next connect.

**Revert (if wanted):**
```bash
adb -s "$VEH" shell "settings delete secure voice_interaction_service; settings delete secure assistant"
adb -s "$VEH" shell "settings put secure voice_recognition_service 'com.arlosoft.macrodroid/.voiceservice.RecognitionServiceTrampoline'"  # original
```

---

## Path B — mic button (long-press) → Gemini, via ClusterNav (needs 1.17)
1.17 makes the "Google / Gemini" target launch Gemini directly (fixes the chooser→Bluetooth). Configure in the app:
1. ClusterNav → **"nút vật lý → trợ lý"** → bật (it auto-enables the accessibility service over dadb).
2. Button = **"Học phím…"** → **NHẤN-GIỮ mic ~2s** → it learns keycode **328** (the long-press code). *(Toast "Đã gán nút: 328".)*
3. Gesture = **"Nhấn" (PRESS)** — ⚠️ NOT "Nhấn giữ". The firmware already emits a distinct code (328) for the hold, delivered as an instant pulse, so "Nhấn giữ" (which measures hold-duration) would **never** match.
4. Target = **"Google / Gemini"**.

**Result:** long-press mic → 328 → ClusterNav launches Gemini (robin voice surface). Short-press mic → `BTN_THUMB2` (different code, not matched) → car assistant 小迪 unchanged. If Path A assistant is set, the overlay is Gemini; tap mic to talk.

**If the long-press ALSO opens something native (e.g. Bluetooth) alongside Gemini:** the firmware's long-press may have its own action. Then either (a) accept both, or (b) learn `BTN_TL2`'s keycode instead (the release code) and test which is cleaner.

---

## Reality check
- **Achievable:** Gemini opens (button or overlay); type or tap-mic to talk.
- **Likely NOT achievable on this head unit:** always-listening **"OK Google" hotword** (Google device-certification limitation — same root as Gemini's "voice not supported" banner). So the button/overlay is the trigger, not hotword.
- If Path A's assistant + reboot makes the native voice button or a gesture open Gemini directly, Path B (the ClusterNav button) becomes optional.
