# Review remediation map

Every item from the two code reviews, and where it was fixed.

## Safety-critical
| Item | Fix |
|---|---|
| SOS only opened smsto: composer | SmsHelper.kt — SmsManager direct send + honest spoken result + composer fallback; SEND_SMS permission in manifest, runtime request with pending-resume |
| Weak fall cancel | token cleared before send; repeating countdown vibration distinct from SOS; voice cancel; announce only when actually cancelled |
| No foreground service | onPause halts pipeline + loud priority "Detection stopped"; full camera FGS listed as next milestone in README/CHANGELOG |
| Model blind spots (stairs/curbs/glass...) | disclosed in first-run disclaimer + tutorial step 4; segmentation label-map mismatch detected and announced |
| Monocular distance tiers | per-device HFOV (Prefs.cameraHfovDeg + docs/CALIBRATION.md) instead of hardcoded 62° |
| Silent segmentation class IDs | Segmenter.labelMapMismatch validated at init, surfaced in status + speech |

## Crash / ANR / correctness
| Item | Fix |
|---|---|
| Geocoder on main thread | Geo.whereAmIText is suspend/IO; API 33+ async API; MainActivity calls via lifecycleScope |
| OCR frame recycle race | CameraController.grabFrame() returns locked copies; cache recycled only inside the lock at swap |
| readSign infinite retry | bounded to 3 attempts, permission checked first, real error spoken |
| TTS queue stall | Speaker watchdog (45 s) force-advances dropped-callback utterances |
| Unknown classes never alerted | Detector FALLBACK_H heights for alert-critical classes; estDistance contract unchanged (tests pass) |
| No off-route handling | navTick deviation check >30 m -> recalculate (max 3, 30 s cooldown) |
| Partial permission grant breaks startup | REQ_PERMS handles each permission independently with spoken summary |
| Resume request loop | resume restarts only when wantsDetection && not already running; denial-forever spoken once |
| Volume keys hijacked | short press = media volume via AudioManager, 700 ms hold = feature |
| Misleading fall SMS | sends for real when permitted; spoken result matches reality |
| Fall-cancel on any touch | returns false when nothing cancelled; token nulled pre-send |
| Stale battery chip | ACTION_BATTERY_CHANGED receiver; spoken warnings on 20%/10% crossings |
| Tutorial dropped by queue cap | steps chained with estimated durations; tutorialDone set only at completion |
| Double-tap vs TalkBack | contentDescriptions added throughout layout; repeat also available via voice ("repeat") |

## Performance / battery
| Item | Fix |
|---|---|
| Analysis while paused | full pipeline stop in onPause (camera unbind, sonar stop, OCR loop removed) |
| Per-pixel YUV reads | bulk row copies + packed-VU fast path + reused NV21 buffer |
| Per-frame allocations/logging | Log.d removed from frame path; bitmaps recycled; JPEG intermediates recycled |
| Sonar allocation/DND | one stream-mode AudioTrack; USAGE_ASSISTANCE_SONIFICATION |
| GPS/power | unchanged interval but pipeline stops in background; noted in README |

## Networking / robustness
| Item | Fix |
|---|---|
| Hardcoded public endpoints | BuildConfig OSRM_BASE/NOMINATIM_BASE + -P overrides; UA with contact; timeouts + one retry |
| Racy online check | NET_CAPABILITY_VALIDATED required |
| Swallowed exceptions | GeoError sealed results (NO_INTERNET/NOT_FOUND/RATE_LIMITED/NETWORK) with distinct speech |
| Step-free dead UI | removed from layout + Prefs |
| Naive command parsing | prefix-anchored matching; "how do I stop navigation"/"I already read it" no longer misfire |
| English-only recognition | EXTRA_LANGUAGE = system locale |

## Repo / hygiene
| Item | Fix |
|---|---|
| Placeholder screenshots | removed; README notes on-device captures with releases |
| Version/UA drift | 1.4.0 everywhere, including BuildConfig UA |
| Repo naming / CI badge | workflow kept; README points at doodle765/GuideLens; rename recommendation documented |
| 00-README-FIRST.txt | removed |
| strings hardcoded / Urdu UI | documented in CONTRIBUTING (extraction planned); TTS + recognition now follow locale |
| Model supply chain | downloadModels verifies TFL3 magic + size; app/models.sha256 pin enforcement; NOTICE file |
| Untested surface | CONTRIBUTING flags instrumented tests as top gap; CI runs unit tests + lint |
