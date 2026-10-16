# Contributing

Thanks for helping make GuideLens safer for blind and elderly users.

## Ground rules

1. **Safety first.** Every change to detection, alerts, SOS, or navigation
   must state how it was tested on a real device, and must not regress the
   unit tests (`AlertPolicyTest`, `DetectorMathTest`, `GeoTest`).
2. **Speak every failure.** If the app can't do something, it must say so
   out loud — a silent failure is a safety bug for a non-visual user.
3. **Never silently drop messages.** The speech queue has a cap by design;
   anything new that competes with it must be priority-flagged or sequenced.
4. **User-facing strings** live inline in Kotlin today; if you add strings,
   keep the existing style (we plan strings.xml extraction — help welcome).
5. **No new hardcoded constants** for distances/FOV without a comment
   explaining the source and a calibration story (see docs/CALIBRATION.md).

## Setup

See README.md (JDK 17, models auto-download on first build).

## Testing

    ./gradlew testDebugUnitTest lint assembleDebug

Add unit tests for any new pure logic. Instrumented tests for the camera
pipeline, TTS queue, sonar, pocket watcher, fall detector and permission
flows are the biggest gap — contributions especially welcome there.
