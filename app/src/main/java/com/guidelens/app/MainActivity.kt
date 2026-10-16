package com.guidelens.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var ocr: OcrReader
    private lateinit var voice: VoiceController
    private lateinit var vibrator: android.os.Vibrator
    private lateinit var alerts: AlertCenter

    private lateinit var scroller: android.widget.ScrollView
    private lateinit var scrHome: View
    private lateinit var scrNav: View
    private lateinit var scrCamera: View
    private lateinit var scrSOS: View
    private lateinit var scrSettings: View
    private lateinit var navStatus: TextView
    private lateinit var camStatus: TextView
    private lateinit var navDestInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var setContact: com.google.android.material.textfield.TextInputEditText

    private lateinit var gpsChip: Chip
    private lateinit var battChip: Chip
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlay: OverlayView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var camera: CameraController
    private lateinit var fused: com.google.android.gms.location.FusedLocationProviderClient
    private var lastLocation: Location? = null

    private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
    private val REQ_PERMS = 101
    private val REQ_CAM = 102
    private val REQ_MIC = 103
    private val REQ_SMS = 104

    private var activeNav: Geo.Route? = null

    private lateinit var sonar: Sonar
    private lateinit var tapDetector: GestureDetector
    private lateinit var pocketWatcher: PocketWatcher
    private lateinit var fallDetector: FallDetector
    private var ocrBusy = false
    private var lastOcrNorm = ""
    private var lastOcrAt = 0L
    private var fallCancel: Runnable? = null

    /** true while the user wants detection; cleared by Stop. */
    private var wantsDetection = false
    private var camDeniedForeverSpoken = false
    private var readSignRetries = 0
    private var pendingSosAfterPermission = false

    // ---- volume key long-press state ----
    private var volDownKey = -1
    private var volLongPressFired = false
    private val volLongPressMs = 700L
    private val volLongPressRunnable = Runnable {
        volLongPressFired = true
        when (volDownKey) {
            KeyEvent.KEYCODE_VOLUME_UP -> { show("sos"); speaker.speak("S O S screen open.") }
            KeyEvent.KEYCODE_VOLUME_DOWN -> whereAmI()
        }
    }

    // ---- battery: live, not stale ----
    private var lastBatteryWarnLevel = 100
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            updateBatteryChip(pct)
            val warnAt = when {
                pct <= 10 && lastBatteryWarnLevel > 10 -> 10
                pct <= 20 && lastBatteryWarnLevel > 20 -> 20
                else -> -1
            }
            if (warnAt > 0) speaker.speak("Warning. Battery at $pct percent.", priority = true)
            lastBatteryWarnLevel = pct
        }
    }

    private val ocrRunnable = object : Runnable {
        override fun run() {
            if (::camera.isInitialized && camera.running && !ocrBusy) {
                // grabFrame() returns an independent copy — no recycle race
                val bmp = camera.grabFrame()
                if (bmp != null) {
                    ocrBusy = true
                    lifecycleScope.launch {
                        val text = withContext(Dispatchers.IO) { ocr.read(bmp) }
                        ocrBusy = false
                        if (text.isNotBlank()) maybeAnnounceSign(text)
                    }
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                lastLocation = it
                updateGpsChip()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        speaker = Speaker(this, prefs)
        alerts = AlertCenter(speaker, prefs, AlertCenter.vibratorOf(this))
        ocr = OcrReader()
        voice = VoiceController(
            this,
            onCommand = { runOnUiThread { handleCommand(it) } },
            onError = { runOnUiThread { speaker.speak("Sorry, I didn't catch that.") } }
        )
        sonar = Sonar()
        fused = LocationServices.getFusedLocationProviderClient(this)

        bindViews()
        wireButtons()
        loadSettingsUI()

        // battery chip now updates live instead of going stale after onCreate
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                speaker.repeat()
                return true
            }
        })

        pocketWatcher = PocketWatcher(this) { covered ->
            runOnUiThread {
                alerts.setMuted(covered)
                sonar.muted = covered
                if (covered) speaker.speak("Phone covered. Detection paused.")
                else speaker.speak("Detection resumed.")
            }
        }

        fallDetector = FallDetector(this) { runOnUiThread { onFallDetected() } }

        if (!allGranted(PERMS)) {
            ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)
        } else {
            startLocation()
        }

        // detection auto-starts only if the camera permission is already there;
        // otherwise it starts from the permission result — no storm of requests
        wantsDetection = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            handler.postDelayed({ startCamera() }, 700)
        }
        handler.postDelayed({ if (hasLocationPerm()) startLocation() }, 900)
        handler.postDelayed(ocrRunnable, 6000)
        showDisclaimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelFallCountdownIfAny()
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        scroller = findViewById(R.id.scroller)
        scrHome = findViewById(R.id.scrHome)
        scrNav = findViewById(R.id.scrNav)
        scrCamera = findViewById(R.id.scrCamera)
        scrSOS = findViewById(R.id.scrSOS)
        scrSettings = findViewById(R.id.scrSettings)
        navStatus = findViewById(R.id.navStatus)
        camStatus = findViewById(R.id.camStatus)
        navDestInput = findViewById(R.id.navDestInput)
        setContact = findViewById(R.id.setContact)
        gpsChip = findViewById(R.id.gpsChip)
        battChip = findViewById(R.id.battChip)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
    }

    private fun show(name: String) {
        scrHome.visibility = if (name == "home") View.VISIBLE else View.GONE
        scrNav.visibility = if (name == "nav") View.VISIBLE else View.GONE
        scrCamera.visibility = if (name == "camera") View.VISIBLE else View.GONE
        scrSOS.visibility = if (name == "sos") View.VISIBLE else View.GONE
        scrSettings.visibility = if (name == "settings") View.VISIBLE else View.GONE
        scroller.post { scroller.scrollTo(0, 0) }
    }

    private fun wireButtons() {
        findViewById<View>(R.id.btnWhereAmI).setOnClickListener { whereAmI() }
        findViewById<View>(R.id.btnNavigate).setOnClickListener { show("nav") }
        findViewById<View>(R.id.btnDetect).setOnClickListener { show("camera"); wantsDetection = true; startCamera() }
        findViewById<View>(R.id.btnAround).setOnClickListener { describeAround() }
        findViewById<View>(R.id.btnRead).setOnClickListener { readSign() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { show("settings") }
        findViewById<View>(R.id.btnSOS).setOnClickListener { show("sos") }

        findViewById<View>(R.id.btnTalk).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { micPermissionThen { voice.start() }; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { voice.stop(); true }
                else -> false
            }
        }

        findViewById<View>(R.id.navBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.navGo).setOnClickListener {
            val q = navDestInput.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) speaker.speak("Please type or say where you want to go.")
            else navigateTo(q)
        }
        findViewById<View>(R.id.navStop).setOnClickListener { stopNavigation() }

        findViewById<View>(R.id.camStopTop).setOnClickListener { stopCameraAndHome() }
        findViewById<View>(R.id.camStop).setOnClickListener { stopCameraAndHome() }

        findViewById<View>(R.id.sosBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.sosSend).setOnClickListener { requestSmsPermissionThen { sendSOS() } }

        findViewById<View>(R.id.setBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<View>(R.id.btnTestVoice).setOnClickListener {
            vibrateSosPattern()
            speaker.speak("Voice test. Both sound and vibration are working.")
        }

        findViewById<View>(R.id.themeBtn).setOnClickListener {
            prefs.themeDark = !prefs.themeDark
            applyNightMode()
            speaker.speak(if (prefs.themeDark) "Dark theme on." else "Light theme on.")
        }
        findViewById<View>(R.id.simpleBtn).setOnClickListener {
            prefs.simple = !prefs.simple
            applySimpleMode()
            speaker.speak(if (prefs.simple) "Simple view on." else "Simple view off.")
        }
    }

    private fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applySimpleMode() {
        val simple = prefs.simple
        listOf(R.id.verbGroup, R.id.sensGroup, R.id.rateLabel, R.id.setRate, R.id.themeGroup)
            .forEach { id -> findViewById<View>(id)?.visibility = if (simple) View.GONE else View.VISIBLE }
    }

    private fun allGranted(perms: Array<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun hasCameraPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasCameraPerm()) {
            // only ask again if the system will actually show the dialog —
            // a permanently-denied grant just loops requestPermissions silently
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            } else if (!camDeniedForeverSpoken) {
                camDeniedForeverSpoken = true
                speaker.speak("Camera permission is denied. Obstacle detection needs it. Please enable camera access for GuideLens in your phone settings.")
            }
            return
        }
        if (!::camera.isInitialized) {
            camera = CameraController(this, previewView, overlay, prefs, speaker, alerts, sonar) { msg ->
                camStatus.text = msg
            }
        }
        if (::camera.isInitialized && camera.running) return
        camera.start()
        sonar.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        fun granted(p: String): Boolean {
            val i = permissions.indexOf(p)
            return i >= 0 && i < grantResults.size && grantResults[i] == PackageManager.PERMISSION_GRANTED
        }
        when (requestCode) {
            REQ_PERMS -> {
                // partial grants are fine: start what we can, say what we can't
                if (granted(Manifest.permission.CAMERA)) {
                    if (wantsDetection) startCamera()
                } else {
                    speaker.speak("Without camera permission, obstacle detection will not work.")
                }
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    startLocation()
                } else {
                    speaker.speak("Without location permission, where am I and navigation will not work.")
                }
            }
            REQ_CAM -> {
                if (granted(Manifest.permission.CAMERA)) startCamera()
                else speaker.speak("Camera permission was denied. Obstacle detection will not work.")
            }
            REQ_MIC -> {
                if (granted(Manifest.permission.RECORD_AUDIO)) voice.start()
                else speaker.speak("Microphone permission was denied. Voice commands will not work.")
            }
            REQ_SMS -> {
                if (granted(Manifest.permission.SEND_SMS)) {
                    if (pendingSosAfterPermission) {
                        pendingSosAfterPermission = false
                        sendSOS()
                    } else {
                        speaker.speak("SMS permission granted. Emergency messages will now send automatically.")
                    }
                } else {
                    speaker.speak("SMS permission denied. S O S will open a ready-to-send message instead.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm()) return
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) { lastLocation = loc; updateGpsChip() }
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L).build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) { }
    }

    private fun updateGpsChip() {
        val loc = lastLocation
        gpsChip.text = if (loc != null) " GPS %.4f, %.4f".format(loc.latitude, loc.longitude)
        else " GPS --"
    }

    private fun whereAmI() {
        val loc = lastLocation
        if (loc == null) {
            speaker.speak("I don't have your location yet. Please wait a moment and try again.")
            return
        }
        // Geocoder is blocking IPC — must not run on the main thread (ANR fix)
        lifecycleScope.launch {
            val result = Geo.whereAmIText(this@MainActivity, loc)
            val text = when (result) {
                is Geo.WhereResult.Address ->
                    "${result.text}. Latitude ${"%.4f".format(loc.latitude)}, longitude ${"%.4f".format(loc.longitude)}."
                is Geo.WhereResult.Coordinates ->
                    "I can't determine your exact address. Your approximate position is latitude " +
                    "${"%.4f".format(loc.latitude)}, longitude ${"%.4f".format(loc.longitude)}."
                Geo.WhereResult.NoInternet ->
                    "No internet access. I can't look up your address right now."
                Geo.WhereResult.Failed ->
                    "I couldn't determine your address, but your location is available."
            }
            speaker.speak(text)
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateTo(query: String) {
        val loc = lastLocation
        if (loc == null) {
            speaker.speak("I don't have your location yet. Please wait a moment and try again.")
            return
        }
        lifecycleScope.launch {
            speaker.speak("Searching for $query.")
            when (val g = Geo.geocode(this@MainActivity, query)) {
                is GeoResult.Err -> { speakGeoError(g.error, "find $query"); return@launch }
                is GeoResult.Ok -> {
                    val dest = g.value
                    speaker.speak("Found ${dest.label}. Downloading walking route.")
                    when (val r = Geo.fetchRoute(this@MainActivity, LatLng(loc.latitude, loc.longitude), dest)) {
                        is GeoResult.Err -> speakGeoError(r.error, "get a walking route to ${dest.label}")
                        is GeoResult.Ok -> beginRoute(r.value)
                    }
                }
            }
        }
    }

    private fun beginRoute(route: Geo.Route) {
        activeNav = route
        show("nav")
        val total = Geo.routeLen(route.coords).toInt()
        speaker.speak("Starting navigation to ${route.dest.label}. Distance about $total meters. Walk straight to begin. " +
                "Note: walking routes may not follow sidewalks or crossings — use your cane and judgment at every crossing.")
        navStatus.text = "Navigating to ${route.dest.label} — ~$total m"
        handler.removeCallbacks(navTick)
        handler.post(navTick)
    }

    private fun speakGeoError(err: GeoError, what: String) {
        speaker.speak(when (err) {
            GeoError.NO_INTERNET -> "I need an internet connection to $what. Everything else works offline."
            GeoError.NOT_FOUND -> "I couldn't $what. Try a different name."
            GeoError.RATE_LIMITED -> "The map service is busy right now. Please try again in a minute."
            GeoError.NETWORK -> "The map service isn't responding. Please try again."
        })
    }

    private val navTick = object : Runnable {
        override fun run() {
            val r = activeNav ?: return
            val loc = lastLocation ?: run { handler.postDelayed(this, 3000); return }
            val pt = LatLng(loc.latitude, loc.longitude)
            val now = System.currentTimeMillis()

            // ---- off-route detection & recalculation (was wired but never used) ----
            if (now - r.lastRecalc > 30_000 && r.recalcCount < 3) {
                val off = Geo.nearestDistToRoute(pt, r.coords)
                if (off > 30.0) {
                    r.lastRecalc = now
                    r.recalcCount++
                    speaker.speak("You seem to be off the route. Recalculating.")
                    lifecycleScope.launch {
                        when (val rr = Geo.fetchRoute(this@MainActivity, pt, r.dest)) {
                            is GeoResult.Ok -> beginRoute(rr.value)
                            is GeoResult.Err -> speakGeoError(rr.error, "recalculate the route")
                        }
                    }
                    return
                }
            }

            val cur = r.steps.getOrNull(r.stepIdx)
            if (cur == null) { finishNav(); return }
            val d = Geo.hav(pt, LatLng(cur.lat, cur.lng)).toInt()
            val txt = Geo.maneuverText(cur)
            if (!r.announcedPre && d in 6..30) {
                speaker.speak("In about $d meters, $txt")
                r.announcedPre = true
            }
            if (d < 6 && !r.announcedNow) {
                speaker.speak(txt)
                r.announcedNow = true
            }
            if (d < 4) {
                r.stepIdx++
                r.announcedPre = false
                r.announcedNow = false
                if (r.stepIdx >= r.steps.size) { finishNav(); return }
            }
            navStatus.text = "Next: ${Geo.maneuverText(r.steps[r.stepIdx])} ($d m)"
            handler.postDelayed(this, 2500)
        }
    }

    private fun finishNav() {
        activeNav = null
        handler.removeCallbacks(navTick)
        speaker.speak("You have arrived. Navigation finished.")
        navStatus.text = "You have arrived."
    }

    private fun stopNavigation() {
        activeNav = null
        handler.removeCallbacks(navTick)
        speaker.speak("Navigation stopped.")
        navStatus.text = "Idle."
        show("home")
    }

    private fun stopCameraAndHome() {
        wantsDetection = false
        if (::camera.isInitialized) camera.stop()
        sonar.stop()
        show("home")
    }

    private fun describeAround() {
        if (!::camera.isInitialized || !camera.running) {
            speaker.speak("Detection is not running yet. Starting it now.")
            wantsDetection = true
            startCamera()
            return
        }
        val dets = camera.lastSeen
        val zone = camera.lastZone
        if (dets.isEmpty() && zone == null) {
            speaker.speak("I don't see any obstacles right now.")
            return
        }
        val sb = StringBuilder()
        dets.take(5).forEach { d ->
            val distTxt = d.dist?.let { " %.1f meters".format(it) } ?: ""
            sb.append("${d.label}$distTxt ${d.side}. ")
        }
        zone?.let { sb.append("Obstacle ahead ${"%.1f".format(it.dist)} meters. ") }
        val text = sb.toString().trim()
        speaker.speak(text)
        camStatus.text = text
    }

    private fun readSign() {
        if (readSignRetries == 0) {
            show("camera")
            wantsDetection = true
        }
        val bmp = if (::camera.isInitialized && camera.running) camera.grabFrame() else null
        if (bmp == null) {
            // bounded retry: the old chain re-fired every 1.5s forever
            if (readSignRetries >= 3) {
                readSignRetries = 0
                speaker.speak("The camera is not available. Please check camera permission in your phone settings, then try again.")
                return
            }
            if (!hasCameraPerm()) {
                speaker.speak("I need camera permission to read signs.")
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
                }
                return
            }
            readSignRetries++
            if (readSignRetries == 1) speaker.speak("Starting the camera so I can read.")
            startCamera()
            handler.postDelayed({ readSign() }, 1500)
            return
        }
        readSignRetries = 0
        speaker.speak("Reading… hold it steady.")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { ocr.read(bmp) }
            if (text.isNotBlank()) {
                lastOcrNorm = normalizeOcr(text)
                lastOcrAt = System.currentTimeMillis()
                speaker.speak("It says: " + text.take(200))
                camStatus.text = "Read: $text"
            } else {
                speaker.speak("I couldn't read any text here. Try holding the camera steady and closer.")
            }
        }
    }

    private fun maybeAnnounceSign(text: String) {
        val norm = normalizeOcr(text)
        val now = System.currentTimeMillis()
        if (norm == lastOcrNorm && now - lastOcrAt < 30_000) return
        lastOcrNorm = norm
        lastOcrAt = now
        speaker.speak("Sign says: " + text.take(140))
    }

    private fun normalizeOcr(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    // ---------- SOS: actually sends when permitted ----------

    private fun requestSmsPermissionThen(fn: () -> Unit) {
        when {
            SmsHelper.canSendDirectly(this) -> fn()
            else -> {
                pendingSosAfterPermission = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
            }
        }
    }

    private fun sendSOS() {
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set. Please add a contact number in settings.")
            show("settings")
            return
        }
        val loc = lastLocation
        val text = if (loc != null)
            "I need help! My approximate location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        else "I need help! (location unavailable)"
        when (val r = SmsHelper.send(this, prefs.contact, text)) {
            SmsHelper.SendResult.SentDirectly -> {
                vibrateSosPattern()
                speaker.speak("Emergency message sent to your contact.", priority = true)
            }
            SmsHelper.SendResult.ComposerOpened -> {
                vibrateSosPattern()
                speaker.speak("Opening messages with your emergency text. Press send.", priority = true)
            }
            SmsHelper.SendResult.NoContact -> {
                speaker.speak("No emergency contact set. Please add a contact number in settings.")
                show("settings")
            }
            SmsHelper.SendResult.NoSmsApp -> {
                speaker.speak("I couldn't find a messaging app on this phone.")
            }
            is SmsHelper.SendResult.Failed -> {
                speaker.speak("The emergency message could not be sent: ${r.reason}")
            }
        }
    }

    private fun micPermissionThen(fn: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) fn()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    /**
     * Volume keys: SHORT press keeps normal media-volume behavior; LONG press
     * (700 ms) triggers SOS / Where am I. The old code consumed every press,
     * making it impossible to change volume in an audio-first app.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                volDownKey = code
                volLongPressFired = false
                handler.postDelayed(volLongPressRunnable, volLongPressMs)
                return true   // consumed; short press will adjust volume on UP
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(volLongPressRunnable)
                if (!volLongPressFired) {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP)
                        AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                }
                volDownKey = -1
                return true
            }
        }
        return true
    }

    private fun handleCommand(raw: String) {
        val text = raw.lowercase().trim()

        // fall countdown voice cancel — a hands-free way out of a false SOS
        if (text == "cancel" || text == "stop" || text == "i'm okay" || text == "i am okay") {
            if (cancelFallCountdownIfAny()) return
        }

        // ordered, prefix-anchored matching — "how do I stop navigation" and
        // "I already read it" no longer misfire
        when {
            text.startsWith("navigate") || text.startsWith("take me") || text.startsWith("directions to") -> {
                val dest = text.removePrefix("navigate to").removePrefix("navigate")
                    .removePrefix("take me to").removePrefix("directions to").trim()
                if (dest.isEmpty()) speaker.speak("Where would you like to go?")
                else navigateTo(dest)
            }
            text.startsWith("stop navigation") -> stopNavigation()
            text.startsWith("where am i") || text.contains("my location") -> whereAmI()
            text.startsWith("read this") || text.startsWith("read that") ||
                text.startsWith("read the") || text.startsWith("read sign") || text == "read" -> readSign()
            text.startsWith("sos") || text.startsWith("emergency") || text.startsWith("help") -> sendSOS()
            text.startsWith("describe") || text.startsWith("around") || text.startsWith("what's around") -> describeAround()
            text.startsWith("camera") || text.startsWith("obstacle") -> { show("camera"); wantsDetection = true; startCamera() }
            text.startsWith("battery") -> speaker.speak("Battery ${battChip.text}.")
            text.startsWith("repeat") -> speaker.repeat()
            else -> speaker.speak("You said $text. Try: where am I, navigate to the market, read this, or help.")
        }
    }

    private fun loadSettingsUI() {
        findViewById<MaterialSwitch>(R.id.chkAudio)?.isChecked = prefs.audio
        findViewById<MaterialSwitch>(R.id.chkHaptics)?.isChecked = prefs.haptics
        findViewById<MaterialSwitch>(R.id.chkSimple)?.isChecked = prefs.simple
        setContact.setText(prefs.contact)

        findViewById<RadioGroup>(R.id.themeGroup)?.check(if (prefs.themeDark) R.id.themeDark else R.id.themeLight)
        findViewById<RadioGroup>(R.id.verbGroup)?.check(
            when (prefs.verbosity) {
                "minimal" -> R.id.verbMinimal
                "full" -> R.id.verbVerbose
                else -> R.id.verbStandard
            }
        )
        findViewById<RadioGroup>(R.id.sensGroup)?.check(
            when (prefs.sensitivity) {
                "near" -> R.id.sensNear
                "far" -> R.id.sensFar
                else -> R.id.sensStandard
            }
        )
        val slider = findViewById<Slider>(R.id.setRate)
        slider?.value = prefs.rate.coerceIn(0.6f, 1.6f)
        updateRateLabel()
        slider?.addOnChangeListener { _, v, _ ->
            prefs.rate = v
            speaker.setRate(v)
            updateRateLabel()
        }

        findViewById<RadioGroup>(R.id.themeGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.themeDark = checkedId == R.id.themeDark
            applyNightMode()
        }
        findViewById<RadioGroup>(R.id.verbGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.verbosity = when (checkedId) {
                R.id.verbMinimal -> "minimal"
                R.id.verbVerbose -> "full"
                else -> "standard"
            }
        }
        findViewById<RadioGroup>(R.id.sensGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.sensitivity = when (checkedId) {
                R.id.sensNear -> "near"
                R.id.sensFar -> "far"
                else -> "standard"
            }
        }
        applySimpleMode()
    }

    private fun updateRateLabel() {
        findViewById<TextView>(R.id.rateLabel)?.text = "Speech speed  %.1f×".format(prefs.rate)
    }

    private fun saveSettings() {
        prefs.audio = findViewById<MaterialSwitch>(R.id.chkAudio)?.isChecked ?: true
        prefs.haptics = findViewById<MaterialSwitch>(R.id.chkHaptics)?.isChecked ?: true
        prefs.simple = findViewById<MaterialSwitch>(R.id.chkSimple)?.isChecked ?: false
        prefs.contact = setContact.text?.toString()?.trim() ?: ""
        applySimpleMode()
        speaker.speak("Settings saved.")
    }

    private fun updateBatteryChip(pct: Int? = null) {
        val p = pct ?: run {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        battChip.text = " $p%"
    }

    private fun showDisclaimer() {
        if (prefs.agreed) {
            if (!prefs.tutorialDone) playTutorial()
            return
        }
        val msg = """
            Please read carefully:

            1) GuideLens is an assistive aid — it cannot replace your white cane, guide dog, or your own judgment.

            2) Do not cross roads based solely on this app.

            3) Distances and obstacle detection are approximate.

            4) The detection model does NOT see stairs, curbs, potholes, open drains, glass doors or low overhead obstacles.

            5) Your camera and microphone stay on-device; only location requests go online for maps.
            """.trimIndent()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Safety & Privacy")
            .setMessage(msg)
            .setPositiveButton("I understand") { d, _ ->
                prefs.agreed = true
                d.dismiss()
                playTutorial()
            }
            .setCancelable(false)
            .show()
        speaker.speak("Welcome to GuideLens. Please listen to the safety information on screen, then press I understand.")
    }

    /**
     * Spoken onboarding — sequenced behind the speech engine so steps can't be
     * dropped by the queue cap, and tutorialDone is set only when it actually
     * finishes (a mid-tutorial kill replays it next launch).
     */
    private fun playTutorial() {
        val steps = listOf(
            "Let's learn the basics.",
            "Hold the phone at chest height, with the camera on the back facing forward, tilted slightly toward the ground. Detection is already running, you don't need to press anything.",
            "I will beep faster as obstacles get closer, and tell you what they are. Double-tap anywhere on the screen to repeat my last message.",
            "I can't see things above your head, like open cupboards, awnings or tree branches. Always check overhead obstacles with your hand or cane.",
            "For your safety, use only one earphone, or a bone conduction headset, so you can still hear traffic and people around you.",
            "Hold the big green button and speak to ask for things, like: navigate to the market, where am I, read this, or help. You can also say cancel to stop an emergency countdown.",
            "That's it. Hold the volume up button to open S O S, and volume down to hear where you are. A short press still changes the volume. GuideLens is now watching for obstacles."
        )
        playTutorialStep(steps, 0)
    }

    private fun playTutorialStep(steps: List<String>, idx: Int) {
        if (idx >= steps.size) {
            prefs.tutorialDone = true
            return
        }
        // estimate utterance duration (~90 words/min speech + engine overhead)
        val estMs = steps[idx].split(" ").size * 650L + 1500L
        speaker.speak(steps[idx], priority = idx == 0)
        handler.postDelayed({ playTutorialStep(steps, idx + 1) }, estMs)
    }

    private fun onFallDetected() {
        vibrateSosPattern()
        speaker.speak(
            "I think you may have fallen. Are you okay? Tap the screen, or say cancel, if you are fine. " +
            "Otherwise I will send an emergency message in 15 seconds.", priority = true
        )
        // distinct repeating vibration while counting down (different from SOS pattern)
        handler.post(fallCountdownVibrate)
        val r = Runnable {
            // clear the token BEFORE sending so a tap landing right at send time
            // doesn't claim it "cancelled" an already-sent message
            fallCancel = null
            handler.removeCallbacks(fallCountdownVibrate)
            sendSOS()
        }
        fallCancel = r
        handler.postDelayed(r, 15_000)
    }

    private val fallCountdownVibrate = object : Runnable {
        override fun run() {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120, 120))
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(120)
                }
            } catch (e: Exception) { }
            if (fallCancel != null) handler.postDelayed(this, 1500)
        }
    }

    /** Returns true if a countdown was actually cancelled. */
    private fun cancelFallCountdownIfAny(): Boolean {
        val r = fallCancel ?: return false
        handler.removeCallbacks(r)
        handler.removeCallbacks(fallCountdownVibrate)
        fallCancel = null
        speaker.speak("Okay. Emergency message cancelled.")
        return true
    }

    private fun vibrateSosPattern() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        pocketWatcher.start()
        fallDetector.start()
        // restart detection only if the user wanted it and it isn't already
        // running — no more requestPermissions storm on every resume
        if (wantsDetection && (!::camera.isInitialized || !camera.running) && hasCameraPerm()) {
            startCamera()
        }
        handler.postDelayed(ocrRunnable, 4000)
    }

    override fun onPause() {
        super.onPause()
        pocketWatcher.stop()
        fallDetector.stop()
        // Halt the whole pipeline when the app isn't visible: no detection,
        // TTS or GPS churn with the screen off; and a blind user is told LOUDLY
        // that protection has stopped instead of assuming it silently.
        val wasRunning = ::camera.isInitialized && camera.running
        if (wasRunning) {
            camera.stop()
            sonar.stop()
            cancelFallCountdownIfAny()
            speaker.speak("Detection stopped. GuideLens is in the background.", priority = true)
        }
        handler.removeCallbacks(ocrRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
        if (::camera.isInitialized) camera.shutdown()
        sonar.stop()
        pocketWatcher.stop()
        fallDetector.stop()
        voice.destroy()
        try { fused.removeLocationUpdates(locationCallback) } catch (e: Exception) { }
        speaker.shutdown()
    }
}
