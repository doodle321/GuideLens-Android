# Changelog

## 1.4.0
Safety-critical:
- SOS now sends via SmsManager when SEND_SMS is granted; composer fallback
  kept, and the spoken result honestly says which one happened.
- Fall countdown: cancel token cleared before send (no false "cancelled"
  announcements), repeating vibration during countdown, voice cancel ("cancel").
- Loud priority announcement when detection stops because the app is
  backgrounded.
- Segmentation label-map mismatch detected at startup and reported aloud.
- COCO classes with no calibrated height (stop sign, hydrant, door, ...)
  now get fallback-height distance estimates and ARE alerted.
- Off-route detection: navTick checks deviation from the route and
  recalculates (max 3 times) instead of guiding to an unreachable point.

Crash/ANR/robustness:
- whereAmI reverse geocoding moved off the main thread (ANR fix); API 33+
  async Geocoder path added.
- OCR frame cache recycle race eliminated (grabFrame() returns locked copies).
- readSign retry loop bounded (3 attempts) then speaks a real error.
- Speaker watchdog un-stalls the queue when a TTS engine drops callbacks.
- Partial permission grants now start camera/location independently and say
  exactly what is disabled; no requestPermissions storm on every resume.
- Volume keys: short press = media volume, 700 ms hold = SOS / Where am I.
- Battery chip live via ACTION_BATTERY_CHANGED; spoken warnings on
  threshold crossings.
- Network errors are distinguishable (offline / not found / rate-limited /
  network) and spoken accordingly; captive-portal Wi-Fi no longer passes
  as "online".
- Voice recognition language follows the system locale.
- Removed dead "prefer step-free routes" setting.
- Camera+segmentation: per-frame debug logging removed, YUV conversion uses
  bulk row copies with a reused NV21 buffer, one stream-mode AudioTrack per
  session with USAGE_ASSISTANCE_SONIFICATION (audible under DND).
- Detection FOV is per-device (Prefs.cameraHfovDeg) instead of hardcoded 62°.
- Tutorial sequenced so the queue cap can't drop steps; tutorialDone set
  only on completion.
- Command parsing anchored to prefixes ("how do I stop navigation" and
  "I already read it" no longer misfire).

Build/hygiene:
- R8 + resource shrinking ON (keep rules for TFLite/ML Kit/CameraX included).
- Version catalog, AGP 8.7.3, Kotlin 2.1.20, compile/targetSdk 35, minSdk 26.
- OSRM/Nominatim endpoints + User-Agent + contact email in BuildConfig,
  overridable per build (-Posrm.base=...).
- Models: checksum-verified download path (pin SHA-256 in app/models.sha256).
- Removed 00-README-FIRST.txt, raster mipmaps (adaptive icons), placeholder
  screenshot table; version/UA aligned at 1.4.0.
