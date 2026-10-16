# GuideLens

Assistive navigation for blind and low-vision users — fully offline obstacle
detection, text reading (OCR), walking navigation, and a spoken assistant.

**This is an assistive aid. It does not replace your white cane, guide dog, or
your own judgment. Do not cross roads based solely on this app.** Distances
are approximate monocular estimates; the model does not see stairs, curbs,
potholes, open drains, glass doors, or low overhead obstacles.

## Features

- **Obstacle detection** — on-device SSD MobileNet (camera) + optional
  DeepLabV3 Cityscapes scene segmentation (poles, walls, fences, signs),
  with distance-tiered spoken alerts, vibration patterns, and a sonar
  beep channel that speeds up as obstacles approach.
- **Text reading** — offline ML Kit OCR for signs and documents.
- **Walking navigation** — OSRM foot routing with spoken turn-by-turn
  guidance and off-route detection/recalculation.
- **Voice commands** — hold the big button and speak ("navigate to...",
  "where am I", "read this", "help", "cancel"). Language follows the
  system locale.
- **Emergency SOS** — sends an SMS with your location directly when the
  SMS permission is granted; otherwise opens a ready-to-send message.
  Experimental fall detection triggers a 15-second countdown you can
  cancel by touch or by saying "cancel".
- **Elderly-friendly** — large touch targets, simple view, high-contrast
  dark theme, adjustable speech speed.

## Requirements & quick start

1. JDK 17 and Android Studio (or a Gradle 8.11+ install).
2. The Gradle wrapper JAR is not in this zip (binary). Either open the
   project in Android Studio (it regenerates automatically) or run once:
   `gradle wrapper --gradle-version 8.11.1`
3. ML models (~7 MB) are fetched and verified automatically at the first
   build (`:app:downloadModels`, wired into `preBuild`), or copy
   `*.tflite` from your previous checkout into `app/src/main/assets/`.
4. Build: `./gradlew assembleDebug` (or Run in Android Studio).

## Configuration

Routing/geocoding endpoints are public demo services by default and have
strict usage policies. Self-host or configure at build time:

    ./gradlew assembleRelease         -Posrm.base=https://your-osrm.example         -Pnominatim.base=https://your-nominatim.example         -Pcontact.email=you@example.org

The User-Agent is sent per Nominatim policy (`GuideLens/1.4.0 (email)`).

## Project layout

- `app/src/main/java/com/guidelens/app/` — all Kotlin sources
  (`MainActivity`, `CameraController`, `Detector`, `Segmenter`, `Geo`,
  `AlertPolicy`/`AlertCenter`, `Speaker`, `Sonar`, `SmsHelper`, ...)
- `app/src/main/assets/` — ML models (auto-downloaded; see README.txt there)
- `app/src/test/` — unit tests for the pure decision/geometry logic
- `docs/CALIBRATION.md` — per-device camera FOV calibration for distances
- `tools/download-models.{sh,ps1}` — checksum-verified model fetch

## Privacy

Camera frames, OCR, detection, segmentation, sonar and speech run fully
on-device. Only routing/geocoding requests leave the phone (OSRM/Nominatim),
and SOS sends a text message when you trigger it.

## License

MIT (see LICENSE). Bundled ML models are NOT MIT — see NOTICE.

## Known limitations

- No foreground service: detection stops when the app is backgrounded, with
  a loud spoken warning. A camera foreground service is the next milestone.
- Detection model blind spots (stairs, curbs, ...) are disclosed in the
  first-run safety screen.
- Walking routes use the OSRM foot profile and phone GPS (5–15 m urban
  error); the app says so when a route starts.

*Mirrors the GuideLens PWA; screenshots are captured on-device and attached
to releases.*
