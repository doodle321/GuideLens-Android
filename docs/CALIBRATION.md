# Per-device camera calibration (distance accuracy)

The monocular distance math assumes: real object height x focal length /
apparent height. The focal length comes from the camera's horizontal field
of view (HFOV), which varies by device (~55-80+ degrees). The default is
62 degrees; calibrating once per device model noticeably improves the
7/5/3-meter alert tiers.

## Procedure

1. Pick an object of known height H (e.g. a standard door, H = 2.0 m).
2. Stand a known distance D away (e.g. 3.0 m, measured with a tape).
3. Point the rear camera at it, run a debug build, and read the detected
   box height in pixels (logcat tag GuideLens/Detector, or the overlay
   label).
4. Compute the effective vertical FOV:

       vfov = 2 * atan( (H * frameHeightPx) / (2 * D * boxHeightPx) )
       hfov = 2 * atan( tan(vfov/2) * frameWidthPx / frameHeightPx )

5. Set the preference (any value 40-100 is accepted):

       adb shell am start -n com.guidelens.app.debug/com.guidelens.app.MainActivity
       # then, with the app data dir writable via run-as on debug builds:
       adb shell run-as com.guidelens.app.debug shared_prefs/guidelens.xml ...

   Or add a hidden settings row that writes `cameraHfovDeg` (PR welcome).

6. Re-test: the door should now read ~3.0 m, and a person at 5 m should
   land in the 5 m band.

## If you don't calibrate

Distances shift roughly proportionally to 62/trueHfov — e.g. a 75° camera
underestimates distance by ~20%, alerting later than intended. The STOP
tier (3 m) still triggers, just a bit late.
