package com.banglavoice.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// একটি সাবটাইটেল লাইন (স্ক্রিনে দেখানোর জন্য)
class Cue(val startMs: Long, var endMs: Long, var display: String, var speech: String) {
    // লাইনটি ড্যাশ (-) দিয়ে শুরু = নতুন কেউ কথা বলছে
    var newSpeaker: Boolean = false
}

// একসাথে পড়া হবে এমন একটি পুরো বাক্য (এক বা একাধিক সাবটাইটেল লাইন মিলে)
// একটা বাক্য ভেঙে দুই লাইনে থাকলে আলাদা আলাদা পড়লে রোবটের মতো শোনায়, তাই একসাথে পড়া হয়
class SpeechUnit(val startMs: Long, val firstCue: Int, var lastCue: Int, var text: String, var lastEndMs: Long) {
    // পরের বাক্য ভিডিওর কোন সময়ে শুরু হবে (এর মধ্যেই এই বাক্যের কথা আর বিরতি আঁটাতে হবে)
    var nextStartMs: Long = Long.MAX_VALUE / 4L
    // এই বাক্য পড়া শেষে কতক্ষণ থামা হবে (মিলিসেকেন্ড)
    var pauseMs: Long = 0L
}

// আগে থেকে তৈরি করে রাখা একটি লাইনের কণ্ঠ (অডিও)
class Clip(
    val ok: Boolean,
    val pcm: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    val durMs: Long
)

// এই মুহূর্তে যে লাইনের কণ্ঠ বাজছে
class Speaking(
    val idx: Int,
    val track: AudioTrack,
    val frames: Int,
    val sampleRate: Int,
    var lastHead: Int,
    var lastMoveAt: Long
)

// ফোনে খুঁজে পাওয়া একটি ভিডিও বা SRT ফাইল
class MediaFile(
    val path: String,
    val name: String,
    val folder: String,
    val size: Long,
    val modified: Long,
    val durationMs: Long
) {
    var match = false
}

class DirNode(val dir: File, val depth: Int)

class SimpleSeek(
    private val onChange: (Int, Boolean) -> Unit,
    private val onStart: () -> Unit,
    private val onStop: (Int) -> Unit
) : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        onChange(progress, fromUser)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        onStart()
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        onStop(seekBar?.progress ?: 0)
    }
}

class MainActivity : Activity() {

    companion object {
        const val REQ_VIDEO = 11
        const val REQ_SRT = 12
        const val REQ_PERM = 13
        const val PICK_VIDEO = 1
        const val PICK_SRT = 2
        val VIDEO_EXT = setOf(
            "mp4", "mkv", "webm", "3gp", "3g2", "mov", "m4v", "avi", "ts", "m2ts",
            "mts", "flv", "wmv", "mpg", "mpeg", "vob", "ogv"
        )
        const val AHEAD = 6              // সামনের কয়টি বাক্যের কণ্ঠ আগাম তৈরি থাকবে
        const val TICK_MS = 30L
        const val BASE_RATE = 1.0f       // কণ্ঠের স্বাভাবিক গতি (দরকার হলে অ্যাপ নিজেই বাড়ায়)
        const val VOICE_MAX = 1.25f      // লম্বা বাক্যে কণ্ঠ সর্বোচ্চ এতটা দ্রুত হবে, যাতে স্বাভাবিক শোনায়
        const val SPEED_FLOOR = 0.7f     // ভিডিও সাধারণত এর চেয়ে ধীর হবে না
        const val SPEED_FLOOR_LATE = 0.55f // কণ্ঠ অনেক পিছিয়ে পড়লে অল্প সময়ের জন্য সর্বনিম্ন এই গতি (তবুও থামবে না)
        const val SPEED_UP_MAX = 1.12f   // আগে ধীর হওয়ার সময়টুকু পুষিয়ে নিতে ভিডিও সর্বোচ্চ এতটা দ্রুত হবে
        const val MAX_DEBT_MS = 12000f   // সর্বোচ্চ কতটা সময় পুষিয়ে নেওয়ার জন্য মনে রাখা হবে
        const val LEAD_MS = 80L          // স্পিকার চালু হতে সামান্য সময় লাগে, তাই কণ্ঠ এতটুকু আগে চালু হয়
        const val PAUSE_END_MS = 600L    // বাক্য শেষ হলে (। ? !) দুই কণ্ঠের মাঝে বিরতি
        const val PAUSE_SPEAKER_MS = 500L // পরের কথা অন্য কেউ বললে বিরতি
        const val PAUSE_MID_MS = 400L    // বাক্য মাঝপথে ভাঙলে বিরতি
        const val DUCK_DEFAULT = 0.01f   // বাংলা পড়ার সময় ভিডিওর আসল আওয়াজ ১% (প্রায় শোনা যায় না)
        const val HOLD_GAP_MS = 2000L    // দুই বাংলা কথার মাঝে এর চেয়ে কম ফাঁক হলে আসল আওয়াজ ফিরবে না
        const val PRE_DUCK_MS = 500L     // বাংলা শুরুর এতটা আগে থেকে আসল আওয়াজ কমতে শুরু করবে
        const val FADE_OUT_MS = 450f     // আসল আওয়াজ নরমভাবে কমতে কত সময় লাগবে
        const val FADE_IN_MS = 1600f     // আসল আওয়াজ খুব ধীরে ধীরে ফিরে আসতে কত সময় লাগবে
        const val JOIN_GAP_MS = 600L     // দুই লাইনের ফাঁক এর কম হলে একই বাক্য ধরা হবে
        const val MAX_JOIN_CUES = 3      // এক বাক্যে সর্বোচ্চ কয়টি লাইন
        const val MAX_JOIN_CHARS = 200   // এক বাক্যে সর্বোচ্চ কত অক্ষর
        val EMPTY_CLIP = Clip(false, ByteArray(0), 0, 0, 0, 0L)
        val NL: String = Char(10).toString()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var player: ExoPlayer
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsFailed = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var cues: List<Cue> = emptyList()
    @Volatile private var units: List<SpeechUnit> = emptyList()
    private val cache = ConcurrentHashMap<Int, Clip>()
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val outcomes = ConcurrentHashMap<String, Boolean>()
    @Volatile private var prepGen = 0
    @Volatile private var wantFrom = 0
    @Volatile private var wantedVoice: Voice? = null
    @Volatile private var failCount = 0
    private var appliedVoiceKey: String? = null
    private var voiceList: List<Voice> = emptyList()

    private var speaking: Speaking? = null
    private var nextSpeakIdx = 0
    private var waitStart = 0L
    private var lastVoiceEndAt = 0L      // শেষ কণ্ঠটা কখন থেমেছে (ফোনের ঘড়িতে)
    private var pauseNeeded = 0L         // পরের কণ্ঠ শুরুর আগে কতক্ষণ থামতে হবে
    private var holdDuckUntil = 0L       // ছোট ফাঁকে এই সময় পর্যন্ত আসল আওয়াজ কম থাকবে
    private var fadePos = 1f             // আসল আওয়াজের অবস্থা: ০ = পুরো কমানো, ১ = পুরো আওয়াজ
    private var muteFade = 1f            // আসল আওয়াজ বন্ধ/চালু করার নরম বদল
    private var muteOriginal = false     // ভিডিওর আসল আওয়াজ পুরো বন্ধ (শুধু বাংলা কণ্ঠ)
    private var isLocked = false         // স্ক্রিন লক চালু কিনা
    private var lockDownInside = false
    private var debtMs = 0f              // ভিডিও ধীর হওয়ায় কতটা সময় বেশি লেগেছে (পরে পুষিয়ে নেওয়া হবে)
    private var lastTickAt = 0L
    private var curSpeed = 1f
    private var appliedSpeed = 1f
    private var curVol = 1f
    private var appliedVol = 1f
    private var duckLevel = DUCK_DEFAULT
    private var tickCount = 0
    private var lastShownIdx = -2
    private var userSeeking = false
    private var isFullscreen = false
    private var videoW = 0
    private var videoH = 0
    private var statusMsg = ""
    private var statusMsgUntil = 0L
    private var showSubs = true
    private var fsBarVisible = false
    private var pendingPick = 0
    private var pickerToken = 0
    private var currentVideoBase = ""

    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var emptyHint: TextView
    private lateinit var subtitleView: TextView
    private lateinit var exitFull: TextView
    private lateinit var panel: ScrollView
    private lateinit var curTime: TextView
    private lateinit var durTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnBack: Button
    private lateinit var btnPlay: Button
    private lateinit var btnFwd: Button
    private lateinit var btnFull: Button
    private lateinit var cardFiles: LinearLayout
    private lateinit var btnVideo: Button
    private lateinit var btnSrt: Button
    private lateinit var videoName: TextView
    private lateinit var srtName: TextView
    private lateinit var cardVoice: LinearLayout
    private lateinit var voiceBox: FrameLayout
    private lateinit var voiceSpinner: Spinner
    private lateinit var duckLabel: TextView
    private lateinit var duckSeek: SeekBar
    private lateinit var warnBox: LinearLayout
    private lateinit var warnText: TextView
    private lateinit var btnInstall: Button
    private lateinit var statusText: TextView
    private lateinit var subsSwitch: Switch
    private lateinit var fsBar: LinearLayout
    private lateinit var fsCur: TextView
    private lateinit var fsDur: TextView
    private lateinit var fsSeek: SeekBar
    private lateinit var fsBack: Button
    private lateinit var fsPlay: Button
    private lateinit var fsFwd: Button
    private lateinit var fsSubs: Button
    private lateinit var fsMute: Button
    private lateinit var lockBtn: TextView
    private lateinit var muteSwitch: Switch

    private val hideBarRunnable = Runnable {
        if (isFullscreen && ::player.isInitialized && player.isPlaying && !userSeeking) setFsBar(false)
    }

    // লক অবস্থায় দেখানো 🔒 চিহ্ন কিছুক্ষণ পর নিজে লুকিয়ে যাবে
    private val hideLockRunnable = Runnable {
        if (isLocked) lockBtn.visibility = View.GONE
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ================= শুরু =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("bangla_voice_player", MODE_PRIVATE)
        volumeControlStream = AudioManager.STREAM_MUSIC
        bindViews()
        setupWindow()
        styleUi()
        duckLevel = prefs.getFloat("duckLevel", DUCK_DEFAULT).coerceIn(0f, 0.5f)
        duckSeek.progress = (duckLevel * 100f).roundToInt()
        updateSliderLabels()
        showSubs = prefs.getBoolean("showSubs", true)
        subsSwitch.isChecked = showSubs
        fsSubs.alpha = if (showSubs) 1f else 0.45f
        muteOriginal = prefs.getBoolean("muteOriginal", false)
        muteFade = if (muteOriginal) 0f else 1f
        muteSwitch.isChecked = muteOriginal
        fsMute.text = if (muteOriginal) "🔇" else "🔊"
        setupPlayer()
        setupControls()
        setupGestures()
        cleanOldAudioFiles()
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            runOnUiThread { onTtsInit(status) }
        }, "com.google.android.tts")
        // প্রতিবার অ্যাপ খুললে নতুন করে শুরু: আগের ভিডিও/সাবটাইটেল মনে রাখা হবে না
        // কণ্ঠের গতি এখন অ্যাপ নিজেই ঠিক করে, তাই আগে হাতে বাছা গতি আর পুরনো ১০% আওয়াজের সেটিংও মুছে ফেলা হয়
        prefs.edit().remove("video").remove("srt").remove("pos").remove("rate").remove("duck").apply()
    }

    private fun bindViews() {
        root = findViewById<LinearLayout>(R.id.root)!!
        header = findViewById<LinearLayout>(R.id.header)!!
        videoBox = findViewById<FrameLayout>(R.id.videoBox)!!
        surfaceView = findViewById<SurfaceView>(R.id.surfaceView)!!
        emptyHint = findViewById<TextView>(R.id.emptyHint)!!
        subtitleView = findViewById<TextView>(R.id.subtitleView)!!
        exitFull = findViewById<TextView>(R.id.exitFull)!!
        panel = findViewById<ScrollView>(R.id.panel)!!
        curTime = findViewById<TextView>(R.id.curTime)!!
        durTime = findViewById<TextView>(R.id.durTime)!!
        seekBar = findViewById<SeekBar>(R.id.seekBar)!!
        btnBack = findViewById<Button>(R.id.btnBack)!!
        btnPlay = findViewById<Button>(R.id.btnPlay)!!
        btnFwd = findViewById<Button>(R.id.btnFwd)!!
        btnFull = findViewById<Button>(R.id.btnFull)!!
        cardFiles = findViewById<LinearLayout>(R.id.cardFiles)!!
        btnVideo = findViewById<Button>(R.id.btnVideo)!!
        btnSrt = findViewById<Button>(R.id.btnSrt)!!
        videoName = findViewById<TextView>(R.id.videoName)!!
        srtName = findViewById<TextView>(R.id.srtName)!!
        cardVoice = findViewById<LinearLayout>(R.id.cardVoice)!!
        voiceBox = findViewById<FrameLayout>(R.id.voiceBox)!!
        voiceSpinner = findViewById<Spinner>(R.id.voiceSpinner)!!
        duckLabel = findViewById<TextView>(R.id.duckLabel)!!
        duckSeek = findViewById<SeekBar>(R.id.duckSeek)!!
        warnBox = findViewById<LinearLayout>(R.id.warnBox)!!
        warnText = findViewById<TextView>(R.id.warnText)!!
        btnInstall = findViewById<Button>(R.id.btnInstall)!!
        statusText = findViewById<TextView>(R.id.statusText)!!
        subsSwitch = findViewById<Switch>(R.id.subsSwitch)!!
        fsBar = findViewById<LinearLayout>(R.id.fsBar)!!
        fsCur = findViewById<TextView>(R.id.fsCur)!!
        fsDur = findViewById<TextView>(R.id.fsDur)!!
        fsSeek = findViewById<SeekBar>(R.id.fsSeek)!!
        fsBack = findViewById<Button>(R.id.fsBack)!!
        fsPlay = findViewById<Button>(R.id.fsPlay)!!
        fsFwd = findViewById<Button>(R.id.fsFwd)!!
        fsSubs = findViewById<Button>(R.id.fsSubs)!!
        fsMute = findViewById<Button>(R.id.fsMute)!!
        lockBtn = findViewById<TextView>(R.id.lockBtn)!!
        muteSwitch = findViewById<Switch>(R.id.muteSwitch)!!
    }

    // ================= ডিজাইন =================

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp)
        d.setColor(color)
        return d
    }

    private fun styleButton(b: Button, color: Int) {
        b.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, 14f), null)
        b.setTextColor(Color.WHITE)
        b.setAllCaps(false)
        b.stateListAnimator = null
        b.textSize = 15f
    }

    private fun styleUi() {
        val primary = getColor(R.color.primary)
        val surface = getColor(R.color.surface)
        val surface2 = getColor(R.color.surface2)
        val warn = getColor(R.color.warn)
        styleButton(btnPlay, primary)
        styleButton(btnBack, surface2)
        styleButton(btnFwd, surface2)
        styleButton(btnFull, surface2)
        styleButton(btnVideo, surface2)
        styleButton(btnSrt, surface2)
        styleButton(btnInstall, warn)
        cardFiles.background = rounded(surface, 18f)
        cardVoice.background = rounded(surface, 18f)
        warnBox.background = rounded(surface, 18f)
        voiceBox.background = rounded(surface2, 12f)
        subtitleView.background = rounded(0xB3000000.toInt(), 10f)
        exitFull.background = rounded(0x80000000.toInt(), 22f)
        lockBtn.background = rounded(0x80000000.toInt(), 22f)
        voiceSpinner.backgroundTintList = ColorStateList.valueOf(Color.WHITE)

        // ফুলস্ক্রিনের নিচের কন্ট্রোল বার
        val fade = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000)
        )
        fsBar.background = fade
        val glass = 0x33FFFFFF
        styleButton(fsBack, glass)
        styleButton(fsPlay, primary)
        styleButton(fsFwd, glass)
        styleButton(fsSubs, glass)
        styleButton(fsMute, glass)

        // সাবটাইটেল সুইচের রঙ
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        subsSwitch.thumbTintList = ColorStateList(states, intArrayOf(getColor(R.color.accent), 0xFFCBD5E1.toInt()))
        subsSwitch.trackTintList = ColorStateList(states, intArrayOf(0x992DD4BF.toInt(), 0x6694A3B8))
        muteSwitch.thumbTintList = subsSwitch.thumbTintList
        muteSwitch.trackTintList = subsSwitch.trackTintList
    }

    private fun bn(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '0'..'9') sb.append('০' + (c - '0')) else sb.append(c)
        }
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        val t = max(0L, ms) / 1000L
        val h = t / 3600L
        val m = (t % 3600L) / 60L
        val s = t % 60L
        val str = if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
        return bn(str)
    }

    private fun updateSliderLabels() {
        duckLabel.text = "কণ্ঠ চলার সময় ভিডিওর আসল আওয়াজ: " + bn((duckLevel * 100f).roundToInt().toString()) + "%"
    }

    private fun flash(msg: String) {
        statusMsg = msg
        statusMsgUntil = SystemClock.uptimeMillis() + 3500L
        statusText.text = msg
    }

    private fun showWarn(msg: String) {
        warnText.text = msg
        warnBox.visibility = View.VISIBLE
    }

    // ================= স্ক্রিন ও ফুলস্ক্রিন =================

    private fun layoutFlags(): Int {
        return View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = layoutFlags()
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) handler.post { applyLayout() }
        }
        videoBox.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) handler.post { fitSurface() }
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        if (isFullscreen) {
            root.setPadding(0, 0, 0, 0)
        } else if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(i.left, i.top, i.right, i.bottom)
        } else {
            root.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
        }
        handler.post { applyLayout() }
    }

    private fun applyLayout() {
        val rw = root.width - root.paddingLeft - root.paddingRight
        val rh = root.height - root.paddingTop - root.paddingBottom
        if (rw <= 0 || rh <= 0) return
        val lp = videoBox.layoutParams as LinearLayout.LayoutParams
        val wanted = if (isFullscreen) {
            rh
        } else {
            val ratio = if (videoW > 0 && videoH > 0) videoH.toFloat() / videoW.toFloat() else 9f / 16f
            min((rw * ratio).roundToInt(), (rh * 0.55f).roundToInt())
        }
        if (lp.height != wanted) {
            lp.height = wanted
            videoBox.layoutParams = lp
        }
        fitSurface()
    }

    private fun fitSurface() {
        val bw = videoBox.width
        val bh = videoBox.height
        if (bw <= 0 || bh <= 0) return
        var w = bw
        var h = bh
        if (videoW > 0 && videoH > 0) {
            val scale = min(bw.toFloat() / videoW.toFloat(), bh.toFloat() / videoH.toFloat())
            w = (videoW * scale).roundToInt()
            h = (videoH * scale).roundToInt()
        }
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = layoutFlags() or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = layoutFlags()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            header.visibility = View.GONE
            panel.visibility = View.GONE
            root.setPadding(0, 0, 0, 0)
            subtitleView.textSize = 22f
            requestedOrientation = if (videoW > 0 && videoW < videoH) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            hideSystemBars()
            setFsBar(true)
        } else {
            header.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            subtitleView.textSize = 18f
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
            setFsBar(false)
        }
        root.requestApplyInsets()
        handler.post { applyLayout() }
    }

    // ফুলস্ক্রিনের নিচের কন্ট্রোল বার (ভিডিও টানার লাইন সহ) দেখানো বা লুকানো
    private fun setFsBar(show: Boolean) {
        handler.removeCallbacks(hideBarRunnable)
        if (show && isFullscreen) {
            fsBarVisible = true
            fsBar.visibility = View.VISIBLE
            exitFull.visibility = View.VISIBLE
            updateTimeUi(player.currentPosition)
            fsBar.post {
                if (fsBarVisible) subtitleView.animate().translationY(-fsBar.height.toFloat()).setDuration(150L).start()
            }
            handler.postDelayed(hideBarRunnable, 3500L)
        } else {
            fsBarVisible = false
            fsBar.visibility = View.GONE
            exitFull.visibility = View.GONE
            subtitleView.animate().translationY(0f).setDuration(150L).start()
        }
        refreshLockBtn()
    }

    // কন্ট্রোল ব্যবহার করলে লুকানোর সময় আবার নতুন করে গোনা
    private fun bumpFsBar() {
        if (isFullscreen && fsBarVisible) {
            handler.removeCallbacks(hideBarRunnable)
            handler.postDelayed(hideBarRunnable, 3500L)
        }
    }

    // ================= স্ক্রিন লক =================

    // 🔓 চিহ্ন কখন দেখা যাবে: সাধারণ স্ক্রিনে সবসময়, ফুলস্ক্রিনে কন্ট্রোল বারের সাথে
    private fun refreshLockBtn() {
        if (isLocked) return
        handler.removeCallbacks(hideLockRunnable)
        val hasVideo = ::player.isInitialized && player.mediaItemCount > 0
        lockBtn.text = "🔓"
        lockBtn.visibility = if (hasVideo && (!isFullscreen || fsBarVisible)) View.VISIBLE else View.GONE
    }

    // লক অবস্থায় স্ক্রিনে ছুঁলে শুধু 🔒 চিহ্নটা আড়াই সেকেন্ডের জন্য দেখা যাবে, আর কিছু না
    private fun showLockHint() {
        lockBtn.text = "🔒"
        lockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideLockRunnable)
        handler.postDelayed(hideLockRunnable, 2500L)
    }

    private fun setLocked(v: Boolean) {
        isLocked = v
        lockDownInside = false
        handler.removeCallbacks(hideLockRunnable)
        if (v) {
            setFsBar(false)
            showLockHint()
        } else {
            refreshLockBtn()
            if (isFullscreen) setFsBar(true)
        }
    }

    private fun touchOnView(v: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val slop = dp(14f)
        return ev.rawX >= loc[0] - slop && ev.rawX <= loc[0] + v.width + slop &&
            ev.rawY >= loc[1] - slop && ev.rawY <= loc[1] + v.height + slop
    }

    // লক চালু থাকলে স্ক্রিনের কোনো ছোঁয়া ভিডিও বা বোতাম পর্যন্ত যাবে না
    // শুধু 🔒 চিহ্নটা দেখা গেলে সেটায় ট্যাপ করলে লক খুলবে
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isLocked) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lockDownInside = lockBtn.visibility == View.VISIBLE && touchOnView(lockBtn, ev)
            }
            MotionEvent.ACTION_UP -> {
                if (lockDownInside && lockBtn.visibility == View.VISIBLE && touchOnView(lockBtn, ev)) {
                    setLocked(false)
                } else {
                    showLockHint()
                }
                lockDownInside = false
            }
            MotionEvent.ACTION_CANCEL -> lockDownInside = false
        }
        return true
    }

    // ================= ভিডিওর আসল আওয়াজ বন্ধ/চালু =================

    private fun setMuteOriginal(v: Boolean) {
        if (muteOriginal != v) {
            muteOriginal = v
            prefs.edit().putBoolean("muteOriginal", v).apply()
            flash(if (v) "🔇 ভিডিওর আসল আওয়াজ বন্ধ, শুধু বাংলা কণ্ঠ শোনা যাবে" else "🔊 ভিডিওর আসল আওয়াজ আবার চালু")
        }
        if (muteSwitch.isChecked != v) muteSwitch.isChecked = v
        fsMute.text = if (v) "🔇" else "🔊"
        if (::player.isInitialized && !player.isPlaying) applyVolumeNow()
    }

    // স্ক্রিনে সাবটাইটেলের লেখা দেখানো চালু/বন্ধ (কণ্ঠ সবসময় চলবে)
    private fun setShowSubs(v: Boolean) {
        if (showSubs != v) {
            showSubs = v
            prefs.edit().putBoolean("showSubs", v).apply()
            flash(if (v) "💬 স্ক্রিনে সাবটাইটেল দেখানো হবে" else "💬 সাবটাইটেল লুকানো হয়েছে, কণ্ঠ চলতে থাকবে")
        }
        if (subsSwitch.isChecked != v) subsSwitch.isChecked = v
        fsSubs.alpha = if (v) 1f else 0.45f
        lastShownIdx = -2
        if (::player.isInitialized) updateSubtitleView(player.currentPosition)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) hideSystemBars()
    }

    override fun onBackPressed() {
        // লক অবস্থায় Back কাজ করবে না, শুধু 🔒 চিহ্ন দেখাবে
        if (isLocked) {
            showLockHint()
            return
        }
        if (isFullscreen) {
            toggleFullscreen()
            return
        }
        showExitDialog()
    }

    // ব্যাক চাপলে অ্যাপ বন্ধ করার অপশন
    private fun showExitDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("🚪 অ্যাপ বন্ধ করবেন?")
            .setMessage("অ্যাপ পুরোপুরি বন্ধ হয়ে যাবে, ব্যাকগ্রাউন্ডে কিছুই চলবে না। পরের বার খুললে নতুন করে ভিডিও আর সাবটাইটেল বেছে নিতে হবে।")
            .setPositiveButton("বন্ধ করুন") { _, _ -> exitApp() }
            .setNegativeButton("না, থাকি", null)
            .show()
    }

    private var released = false

    // সব কিছু থামিয়ে মেমোরি খালি করা (একবারই চলে)
    private fun releaseAll() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        pickerToken++
        finishSpeaking()
        if (::player.isInitialized) {
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
            }
        }
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
        }
        for (l in latches.values) l.countDown()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        cache.clear()
        cleanOldAudioFiles()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // অ্যাপ সম্পূর্ণ বন্ধ: সাম্প্রতিক অ্যাপের তালিকা থেকেও সরে যাবে
    private fun exitApp() {
        prefs.edit().remove("video").remove("srt").remove("pos").commit()
        releaseAll()
        finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 400L)
    }

    // ================= ভিডিও প্লেয়ার =================

    private fun setupPlayer() {
        // ফোন এলে বা অন্য অ্যাপে গান চালালে ভিডিও নিজে থেমে যাবে,
        // হেডফোন খুলে ফেললেও থেমে যাবে (হঠাৎ জোরে স্পিকারে বাজবে না)
        val movieAudio = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(movieAudio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setVideoSurfaceView(surfaceView)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                btnPlay.text = if (playWhenReady) "⏸  থামান" else "▶  চালান"
                fsPlay.text = if (playWhenReady) "⏸" else "▶"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    emptyHint.visibility = View.GONE
                    updateTimeUi(player.currentPosition)
                } else if (playbackState == Player.STATE_ENDED) {
                    finishSpeaking()
                    setSpeedNow(1f)
                    player.pause()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
                    videoH = videoSize.height
                    handler.post { applyLayout() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                flash("এই ভিডিওটি চালানো যাচ্ছে না। অন্য ভিডিও চেষ্টা করুন।")
            }
        })
    }

    private fun onPlayingChanged(isPlaying: Boolean) {
        val sp = speaking
        if (isPlaying) {
            bumpFsBar()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (sp != null) {
                sp.lastMoveAt = SystemClock.uptimeMillis()
                try {
                    sp.track.play()
                } catch (e: Exception) {
                }
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (sp != null) {
                try {
                    sp.track.pause()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun setSpeedNow(s: Float) {
        curSpeed = s
        appliedSpeed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    private fun togglePlay() {
        if (player.mediaItemCount == 0) {
            flash("আগে একটি ভিডিও বেছে নিন।")
            return
        }
        if (player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
                resetSpeech(0L)
            }
            if (cues.isEmpty()) flash("SRT ফাইল দেওয়া হয়নি, শুধু ভিডিও চলবে।")
            player.play()
        }
    }

    private fun seekToMs(ms: Long) {
        if (player.mediaItemCount == 0) return
        val dur = player.duration
        val maxPos = if (dur > 0L) dur else Long.MAX_VALUE
        val t = ms.coerceIn(0L, maxPos)
        player.seekTo(t)
        resetSpeech(t)
    }

    // সোয়াইপ করলে বাক্যের শুরুতে যাবে, যাতে কণ্ঠ পুরো বাক্যটা পড়ে
    private fun jumpStarts(): List<Long> {
        val us = units
        return if (us.isNotEmpty()) us.map { it.startMs } else cues.map { it.startMs }
    }

    private fun nextSubtitle() {
        val starts = jumpStarts()
        val pos = player.currentPosition
        val idx = starts.indexOfFirst { it > pos + 50L }
        if (idx >= 0) seekToMs(starts[idx])
    }

    private fun prevSubtitle() {
        val starts = jumpStarts()
        val pos = player.currentPosition
        val idx = starts.indexOfLast { it < pos - 800L }
        if (idx >= 0) seekToMs(starts[idx]) else seekToMs(0L)
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isFullscreen) setFsBar(!fsBarVisible) else togglePlay()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > dp(50f) && abs(dx) > abs(dy) * 1.5f) {
                    if (dx > 0) nextSubtitle() else prevSubtitle()
                    return true
                }
                return false
            }
        })
        videoBox.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        fsBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (fsBarVisible && b - t != ob - ot) subtitleView.translationY = -fsBar.height.toFloat()
        }
    }

    // ================= বোতাম ও স্লাইডার =================

    private fun setupControls() {
        btnPlay.setOnClickListener { togglePlay() }
        btnBack.setOnClickListener { seekToMs(player.currentPosition - 10000L) }
        btnFwd.setOnClickListener { seekToMs(player.currentPosition + 10000L) }
        btnFull.setOnClickListener { toggleFullscreen() }
        exitFull.setOnClickListener { toggleFullscreen() }
        btnVideo.setOnClickListener { requestPick(PICK_VIDEO) }
        btnSrt.setOnClickListener { requestPick(PICK_SRT) }
        btnInstall.setOnClickListener { openVoiceInstall() }

        subsSwitch.setOnCheckedChangeListener { _, checked -> setShowSubs(checked) }
        muteSwitch.setOnCheckedChangeListener { _, checked -> setMuteOriginal(checked) }
        fsMute.setOnClickListener {
            setMuteOriginal(!muteOriginal)
            bumpFsBar()
        }
        lockBtn.setOnClickListener { setLocked(true) }
        fsSubs.setOnClickListener {
            setShowSubs(!showSubs)
            bumpFsBar()
        }
        fsPlay.setOnClickListener {
            togglePlay()
            bumpFsBar()
        }
        fsBack.setOnClickListener {
            seekToMs(player.currentPosition - 10000L)
            bumpFsBar()
        }
        fsFwd.setOnClickListener {
            seekToMs(player.currentPosition + 10000L)
            bumpFsBar()
        }
        fsSeek.setOnSeekBarChangeListener(SimpleSeek(
            { p, fromUser ->
                if (fromUser) fsCur.text = formatTime(p.toLong())
            },
            {
                userSeeking = true
                handler.removeCallbacks(hideBarRunnable)
            },
            { p ->
                userSeeking = false
                seekToMs(p.toLong())
                bumpFsBar()
            }
        ))

        seekBar.setOnSeekBarChangeListener(SimpleSeek(
            { p, fromUser ->
                if (fromUser) curTime.text = formatTime(p.toLong())
            },
            { userSeeking = true },
            { p ->
                userSeeking = false
                seekToMs(p.toLong())
            }
        ))

        duckSeek.setOnSeekBarChangeListener(SimpleSeek(
            { p, _ ->
                duckLevel = p / 100f
                updateSliderLabels()
            },
            { },
            { _ -> prefs.edit().putFloat("duckLevel", duckLevel).apply() }
        ))

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = voiceList.getOrNull(position) ?: return
                if (v.name == wantedVoice?.name) return
                wantedVoice = v
                prefs.edit().putString("voice", v.name).apply()
                regenerate()
                flash("নতুন কণ্ঠ বেছে নেওয়া হয়েছে")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    // ================= ফাইল বাছাই =================

    private fun pickFile(req: Int, type: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = type
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            flash("ফাইল বাছাই করার অ্যাপ পাওয়া যায়নি।")
        }
    }

    private fun systemPick(kind: Int) {
        if (kind == PICK_VIDEO) pickFile(REQ_VIDEO, "video/*")
        else if (kind == PICK_SRT) pickFile(REQ_SRT, "*/*")
    }

    // ================= মোবাইলের সব ভিডিও / সব SRT এর তালিকা =================

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPick(kind: Int) {
        if (hasStorageAccess()) {
            openPicker(kind)
            return
        }
        pendingPick = kind
        val msg = if (Build.VERSION.SDK_INT >= 30) {
            "মোবাইলের সব ভিডিও আর সব সাবটাইটেল একসাথে তালিকায় দেখাতে অ্যাপের ফাইল দেখার অনুমতি লাগবে।" + NL + NL +
                "পরের পাতায় বাংলা ভয়েস প্লেয়ার এর পাশের সুইচটা চালু করুন (Allow access to manage all files), তারপর Back চাপুন।" + NL + NL +
                "অ্যাপ কোনো ফাইল মুছবে বা বদলাবে না, শুধু খুঁজে দেখাবে।"
        } else {
            "মোবাইলের সব ভিডিও আর সব সাবটাইটেল একসাথে তালিকায় দেখাতে অ্যাপের ফাইল দেখার অনুমতি লাগবে। পরের বার্তায় Allow চাপুন।"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("📂 ফাইল দেখার অনুমতি")
            .setMessage(msg)
            .setPositiveButton("অনুমতি দিন") { _, _ -> askStorageAccess() }
            .setNegativeButton("নিজে খুঁজব") { _, _ ->
                val k = pendingPick
                pendingPick = 0
                systemPick(k)
            }
            .setOnCancelListener { pendingPick = 0 }
            .show()
    }

    private fun askStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    val k = pendingPick
                    pendingPick = 0
                    systemPick(k)
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val k = pendingPick
        pendingPick = 0
        if (k == 0) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openPicker(k)
        } else {
            flash("অনুমতি দেওয়া হয়নি, তাই নিজে ফাইল খুঁজে নিন।")
            systemPick(k)
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        val s = if (mb >= 1024.0) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%d KB", max(1L, bytes / 1024L))
        return bn(s)
    }

    private fun queryVideoStore(out: MutableMap<String, MediaFile>) {
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Video.VideoColumns.DURATION
        )
        contentResolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                val f = File(path)
                if (!f.exists()) continue
                val name = c.getString(1) ?: f.name
                out[path] = MediaFile(path, name, f.parentFile?.name ?: "", c.getLong(2), c.getLong(3) * 1000L, c.getLong(4))
            }
        }
    }

    private fun querySrtStore(out: MutableMap<String, MediaFile>) {
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        contentResolver.query(uri, proj, MediaStore.MediaColumns.DATA + " LIKE ?", arrayOf("%.srt"), null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                val f = File(path)
                if (!f.exists()) continue
                out[path] = MediaFile(path, f.name, f.parentFile?.name ?: "", c.getLong(1), c.getLong(2) * 1000L, 0L)
            }
        }
    }

    // পুরো মোবাইল (এবং মেমোরি কার্ড) ঘুরে ফাইল খোঁজা, যাতে একটাও বাদ না যায়
    private fun walkStorage(isVideo: Boolean, out: MutableMap<String, MediaFile>, token: Int) {
        val roots = LinkedHashSet<String>()
        roots.add(Environment.getExternalStorageDirectory().absolutePath)
        try {
            for (d in getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) roots.add(p.substring(0, i))
            }
        } catch (e: Exception) {
        }
        val stack = java.util.ArrayDeque<DirNode>()
        for (r in roots) stack.push(DirNode(File(r), 0))
        var visited = 0
        while (!stack.isEmpty() && visited < 400000) {
            if (token != pickerToken) return
            val node = stack.pop()
            val children = node.dir.listFiles() ?: continue
            for (c in children) {
                visited++
                val name = c.name
                if (c.isDirectory) {
                    if (node.depth >= 16 || name.startsWith(".")) continue
                    if (node.dir.name == "Android" && (name == "data" || name == "obb")) continue
                    stack.push(DirNode(c, node.depth + 1))
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                    val ok = if (isVideo) ext in VIDEO_EXT else ext == "srt"
                    if (ok && !out.containsKey(c.absolutePath)) {
                        out[c.absolutePath] = MediaFile(c.absolutePath, name, node.dir.name, c.length(), c.lastModified(), 0L)
                    }
                }
            }
        }
    }

    private fun srtMatchesVideo(srtName: String): Boolean {
        val v = currentVideoBase
        if (v.isEmpty()) return false
        val s = srtName.substringBeforeLast('.').lowercase(Locale.US).trim()
        if (s == v) return true
        for (sep in listOf(".", "_", " ", "-", "(", "[")) {
            if (s.startsWith(v + sep)) return true
        }
        return false
    }

    inner class FileAdapter(private val isVideo: Boolean) : BaseAdapter() {
        var items: List<MediaFile> = emptyList()
        private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val t1 = row.getChildAt(0) as TextView
            val t2 = row.getChildAt(1) as TextView
            val f = items[position]
            val icon = if (f.match) "⭐ " else if (isVideo) "🎬 " else "📄 "
            t1.text = icon + f.name
            val parts = ArrayList<String>()
            if (f.match) parts.add("এই ভিডিওর সাথে মিলে")
            parts.add("📁 " + f.folder)
            if (f.durationMs > 0L) parts.add("⏱ " + formatTime(f.durationMs))
            parts.add(formatSize(f.size))
            if (f.modified > 0L) parts.add(bn(dateFmt.format(Date(f.modified))))
            t2.text = parts.joinToString("  ·  ")
            return row
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.VERTICAL
            val ph = dp(18f).toInt()
            val pv = dp(12f).toInt()
            row.setPadding(ph, pv, ph, pv)
            val t1 = TextView(this@MainActivity)
            t1.setTextColor(getColor(R.color.text))
            t1.textSize = 15f
            t1.maxLines = 2
            t1.ellipsize = TextUtils.TruncateAt.END
            val t2 = TextView(this@MainActivity)
            t2.setTextColor(getColor(R.color.textDim))
            t2.textSize = 12.5f
            t2.maxLines = 1
            t2.ellipsize = TextUtils.TruncateAt.MIDDLE
            t2.setPadding(0, dp(3f).toInt(), 0, 0)
            row.addView(t1)
            row.addView(t2)
            return row
        }
    }

    private fun openPicker(kind: Int) {
        val isVideo = kind == PICK_VIDEO
        val token = ++pickerToken
        val bg = getColor(R.color.bg)
        val textC = getColor(R.color.text)
        val dimC = getColor(R.color.textDim)
        val surface2 = getColor(R.color.surface2)
        val pad = dp(16f).toInt()

        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(bg)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(pad, pad, pad / 2, pad / 2)
        val title = TextView(this)
        title.text = if (isVideo) "🎬 মোবাইলের সব ভিডিও" else "📄 মোবাইলের সব সাবটাইটেল (SRT)"
        title.setTextColor(textC)
        title.textSize = 19f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(textC)
        close.textSize = 22f
        close.gravity = Gravity.CENTER
        close.layoutParams = LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt())
        close.setOnClickListener { dlg.dismiss() }
        head.addView(title)
        head.addView(close)

        val search = EditText(this)
        search.hint = "🔍 নাম লিখে খুঁজুন"
        search.setHintTextColor(dimC)
        search.setTextColor(textC)
        search.textSize = 15f
        search.setSingleLine(true)
        search.background = rounded(surface2, 12f)
        search.setPadding(pad, dp(12f).toInt(), pad, dp(12f).toInt())
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.setMargins(pad, 0, pad, 0)
        search.layoutParams = slp

        val info = TextView(this)
        info.text = "⏳ মোবাইলে খুঁজছি…"
        info.setTextColor(dimC)
        info.textSize = 13f
        info.setPadding(pad + dp(2f).toInt(), dp(10f).toInt(), pad, dp(4f).toInt())

        val list = ListView(this)
        list.divider = null
        list.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val adapter = FileAdapter(isVideo)
        list.adapter = adapter

        val other = Button(this)
        other.text = "📁 তালিকায় নেই? অন্য জায়গা থেকে খুঁজুন"
        styleButton(other, surface2)
        val olp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f).toInt())
        olp.setMargins(pad, dp(8f).toInt(), pad, pad)
        other.layoutParams = olp
        other.setOnClickListener {
            dlg.dismiss()
            systemPick(kind)
        }

        box.addView(head)
        box.addView(search)
        box.addView(info)
        box.addView(list)
        box.addView(other)
        dlg.setContentView(box)

        val w = dlg.window
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            w.statusBarColor = bg
            w.navigationBarColor = bg
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false)
            } else {
                w.decorView.systemUiVisibility = layoutFlags()
            }
        }
        box.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(sys.left, sys.top, sys.right, max(sys.bottom, ime.bottom))
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }

        var allItems: List<MediaFile> = emptyList()
        var scanning = true
        fun applyFilter() {
            val q = search.text.toString().trim().lowercase(Locale.US)
            adapter.items = if (q.isEmpty()) allItems else allItems.filter {
                it.name.lowercase(Locale.US).contains(q) || it.folder.lowercase(Locale.US).contains(q)
            }
            adapter.notifyDataSetChanged()
            val kindName = if (isVideo) "ভিডিও" else "সাবটাইটেল"
            info.text = if (scanning) {
                "⏳ মোবাইলে খুঁজছি… এখন পর্যন্ত " + bn(allItems.size.toString()) + "টি " + kindName + " পাওয়া গেছে"
            } else if (allItems.isEmpty()) {
                "কোনো " + kindName + " পাওয়া যায়নি। নিচের বোতাম দিয়ে নিজে খুঁজে দেখুন।"
            } else if (q.isNotEmpty()) {
                "🔍 " + bn(adapter.items.size.toString()) + "টি মিলেছে (মোট " + bn(allItems.size.toString()) + "টি)"
            } else {
                "✅ মোট " + bn(allItems.size.toString()) + "টি " + kindName + " · নতুনগুলো উপরে"
            }
        }
        fun showItems(items: List<MediaFile>, stillScanning: Boolean) {
            if (!isVideo) for (f in items) f.match = srtMatchesVideo(f.name)
            allItems = items.sortedWith(
                compareByDescending<MediaFile> { it.match }.thenByDescending { it.modified }
            )
            scanning = stillScanning
            applyFilter()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val f = adapter.items.getOrNull(position)
            if (f != null) {
                dlg.dismiss()
                onFilePicked(kind, f)
            }
        }
        dlg.setOnDismissListener {
            if (pickerToken == token) pickerToken++
        }
        dlg.show()

        Thread {
            val found = LinkedHashMap<String, MediaFile>()
            try {
                if (isVideo) queryVideoStore(found) else querySrtStore(found)
            } catch (e: Exception) {
            }
            val first = ArrayList(found.values)
            runOnUiThread { if (pickerToken == token) showItems(first, true) }
            try {
                walkStorage(isVideo, found, token)
            } catch (e: Exception) {
            }
            val all = ArrayList(found.values)
            runOnUiThread { if (pickerToken == token) showItems(all, false) }
        }.start()
    }

    private fun onFilePicked(kind: Int, f: MediaFile) {
        val uri = Uri.fromFile(File(f.path))
        if (kind == PICK_VIDEO) {
            prefs.edit().putString("video", uri.toString()).putLong("pos", 0L).apply()
            loadVideo(uri, 0L)
            flash("🎬 ভিডিও খোলা হয়েছে")
        } else {
            try {
                if (loadSrt(uri)) {
                    prefs.edit().putString("srt", uri.toString()).apply()
                    flash("📄 সাবটাইটেল লোড হয়েছে")
                }
            } catch (e: Exception) {
                flash("SRT ফাইলটি পড়া যায়নি।")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        if (requestCode == REQ_VIDEO) {
            prefs.edit().putString("video", uri.toString()).putLong("pos", 0L).apply()
            loadVideo(uri, 0L)
        } else if (requestCode == REQ_SRT) {
            try {
                if (loadSrt(uri)) prefs.edit().putString("srt", uri.toString()).apply()
            } catch (e: Exception) {
                flash("SRT ফাইলটি পড়া যায়নি।")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: (uri.lastPathSegment ?: "ফাইল")
    }

    private fun loadVideo(uri: Uri, startAt: Long) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        if (startAt > 0L) player.seekTo(startAt)
        player.playWhenReady = false
        val vName = displayName(uri)
        currentVideoBase = vName.substringBeforeLast('.').lowercase(Locale.US).trim()
        videoName.text = "🎬 " + vName
        emptyHint.visibility = View.GONE
        resetSpeech(startAt)
        refreshLockBtn()
    }

    private fun loadSrt(uri: Uri): Boolean {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val parsed = parseSrt(decodeText(bytes))
        if (parsed.isEmpty()) {
            flash("এই ফাইলে কোনো সাবটাইটেল পাওয়া যায়নি।")
            return false
        }
        val built = buildUnits(parsed)
        finishSpeaking()
        prepGen++
        cache.clear()
        cues = parsed
        units = built
        failCount = 0
        srtName.text = "📄 " + displayName(uri) + " · " + bn(parsed.size.toString()) + "টি লাইন"
        resetSpeech(if (player.mediaItemCount > 0) player.currentPosition else 0L)
        return true
    }

    // ================= SRT পড়া =================

    private fun decodeText(bytes: ByteArray): String {
        val s = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            String(bytes, Charsets.UTF_16LE)
        } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, Charsets.UTF_16BE)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return s.replace(Char(0xFEFF).toString(), "")
    }

    private fun parseTs(raw: String): Long? {
        val t = raw.trim().replace(',', '.')
        val parts = t.split(':')
        if (parts.size < 2) return null
        val h = if (parts.size >= 3) (parts[parts.size - 3].trim().toLongOrNull() ?: return null) else 0L
        val m = parts[parts.size - 2].trim().toLongOrNull() ?: return null
        val secParts = parts[parts.size - 1].trim().split('.')
        val sec = secParts[0].trim().toLongOrNull() ?: return null
        var ms = 0L
        if (secParts.size > 1) {
            val digits = secParts[1].filter { it.isDigit() }.take(3).padEnd(3, '0')
            ms = digits.toLongOrNull() ?: 0L
        }
        return ((h * 60L + m) * 60L + sec) * 1000L + ms
    }

    private fun removeBetween(s: String, open: Char, close: Char): String {
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            if (c == open) {
                depth++
            } else if (c == close && depth > 0) {
                depth--
            } else if (depth == 0) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder()
        var space = false
        for (c in s) {
            if (c.isWhitespace()) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    // HTML চিহ্ন (&amp; ইত্যাদি) সাধারণ লেখায় বদলানো
    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&nbsp;", " ")
            .replace("&quot;", "" + '"')
            .replace("&#39;", Char(39).toString())
            .replace("&apos;", Char(39).toString())
            .replace("&lt;", "")
            .replace("&gt;", "")
            .replace("&amp;", "&")
    }

    // ইমোজি আর বাড়তি চিহ্ন বাদ দেওয়া, নাহলে কণ্ঠ এগুলোর নাম পড়ে ফেলে
    private fun stripSymbols(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFE0F) continue
            val drop = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                cp == '#'.code || cp == '*'.code || cp == '~'.code || cp == '_'.code ||
                cp == '|'.code || cp == '^'.code || cp == '='.code
            if (drop) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    // ইংরেজি বড় হাতের বক্তার নাম (যেমন JOHN:) পড়া হবে না
    private fun removeSpeakerLabel(s: String): String {
        val colon = s.indexOf(':')
        if (colon < 2 || colon > 24 || colon >= s.length - 1) return s
        val label = s.substring(0, colon)
        if (!label.any { it in 'A'..'Z' }) return s
        if (!label.all { it in 'A'..'Z' || it == ' ' || it == '.' || it == '-' || it == Char(39) }) return s
        return s.substring(colon + 1).trim()
    }

    // ... বা .. বা … কে একটি … চিহ্নে আনা
    private fun normalizeDots(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '…' || (c == '.' && i + 1 < s.length && s[i + 1] == '.')) {
                while (i < s.length && (s[i] == '.' || s[i] == '…')) i++
                if (sb.isEmpty() || sb.last() != '…') sb.append('…')
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val punctChars = setOf('।', '॥', '?', '!', ',', ';', ':', '.', '…')
    private val endChars = setOf('।', '॥', '?', '!', '.')
    private val closeChars = setOf('"', Char(39), '”', '’', '»', ')')

    // যতিচিহ্ন গোছানো: !!! কে !, চিহ্নের আগের ফাঁকা বাদ, শুরুতে থাকা চিহ্ন বাদ
    private fun tidySpeech(s: String): String {
        val sb = StringBuilder()
        for (c in collapseSpaces(s)) {
            if (c in punctChars) {
                while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                if (sb.isEmpty()) continue
                val last = sb.last()
                if (last == c) continue
                if (c == '।' && last == '.') {
                    sb.setCharAt(sb.length - 1, '।')
                    continue
                }
                sb.append(c)
            } else {
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    // বাক্যটি কি এখানেই শেষ (দাঁড়ি, প্রশ্ন বা আশ্চর্য চিহ্ন আছে)?
    private fun endsSentence(s: String): Boolean {
        var t = s.trimEnd()
        while (t.isNotEmpty() && t.last() in closeChars) t = t.dropLast(1).trimEnd()
        return t.isNotEmpty() && t.last() in endChars
    }

    // দুই টুকরো জোড়া: আগেরটা … দিয়ে শেষ হলে (মানুষ একটু থেমে বলছে) মাঝে কমা দিয়ে ছোট বিরতি
    private fun joinSpeech(a: String, b: String): String {
        val at = a.trimEnd()
        val pause = at.endsWith("…")
        val left = at.trimEnd('…').trimEnd()
        val right = b.trimStart().trimStart('…').trimStart()
        return tidySpeech(left + (if (pause) ", " else " ") + right)
    }

    // পড়ার আগে শেষবার গোছানো: মাঝের … = ছোট বিরতি (কমা)
    private fun finishSpeech(s: String): String {
        var t = tidySpeech(s).trim('…', ' ')
        t = t.replace("…", ",")
        return tidySpeech(t)
    }

    private fun makeCue(start: Long, end: Long, lines: List<String>): Cue? {
        val shown = ArrayList<String>()
        val spoken = ArrayList<String>()
        var firstDash = false
        for (raw in lines) {
            val noTags = decodeEntities(removeBetween(removeBetween(raw, '<', '>'), '{', '}')).trim()
            if (noTags.isNotEmpty()) shown.add(noTags)
            var s = removeBetween(noTags, '[', ']').trim()
            // পুরো লাইন বন্ধনীর ভেতরে থাকলে সেটা শব্দের বর্ণনা, যেমন (হাসি) — পড়া হবে না
            if (s.length >= 2 && s.first() == '(' && s.last() == ')') s = ""
            val dash = s.isNotEmpty() && (s[0] == '-' || s[0] == '–' || s[0] == '—')
            s = s.trimStart('-', '–', '—').trim()
            s = removeSpeakerLabel(s)
            s = stripSymbols(s.replace("&", " এবং "))
            s = tidySpeech(normalizeDots(s))
            if (s.isEmpty()) continue
            if (spoken.isEmpty()) {
                firstDash = dash
            } else if (dash) {
                // দুইজনের কথোপকথন: আগের জনের কথা শেষে ছোট থামা
                val prev = spoken[spoken.size - 1]
                if (!endsSentence(prev)) spoken[spoken.size - 1] = prev.trimEnd('…', ' ', ',') + "।"
            }
            spoken.add(s)
        }
        val display = shown.joinToString(NL)
        var speech = ""
        for (p in spoken) speech = if (speech.isEmpty()) p else joinSpeech(speech, p)
        if (display.isBlank() && speech.isBlank()) return null
        val e = if (end > start) end else start + 1500L
        val cue = Cue(start, e, display, speech)
        cue.newSpeaker = firstDash
        return cue
    }

    private fun parseSrt(text: String): List<Cue> {
        val lines = text.lines()
        val raw = ArrayList<Cue>()
        val buf = ArrayList<String>()
        var start = -1L
        var end = -1L
        for (idx in lines.indices) {
            val line = lines[idx].trim()
            if (line.contains("-->")) {
                if (start >= 0L) makeCue(start, end, buf)?.let { raw.add(it) }
                buf.clear()
                val parts = line.split("-->")
                val s = if (parts.size >= 2) parseTs(parts[0]) else null
                val e = if (parts.size >= 2) parseTs(parts[1].trim().split(' ')[0]) else null
                if (s != null && e != null) {
                    start = s
                    end = e
                } else {
                    start = -1L
                    end = -1L
                }
            } else if (start >= 0L && line.isNotEmpty()) {
                val nextIsTiming = idx + 1 < lines.size && lines[idx + 1].contains("-->")
                if (!(nextIsTiming && line.all { it.isDigit() })) buf.add(line)
            }
        }
        if (start >= 0L) makeCue(start, end, buf)?.let { raw.add(it) }
        raw.sortBy { it.startMs }

        // একই সময়ে শুরু হওয়া লাইনগুলো একসাথে করা
        val merged = ArrayList<Cue>()
        for (c in raw) {
            val last = merged.lastOrNull()
            if (last != null && c.startMs < last.startMs + 300L) {
                last.endMs = max(last.endMs, c.endMs)
                last.display = (last.display + NL + c.display).trim()
                last.speech = if (last.speech.isEmpty()) c.speech else if (c.speech.isEmpty()) last.speech else joinSpeech(last.speech, c.speech)
            } else {
                merged.add(c)
            }
        }
        return merged
    }

    // সাবটাইটেল লাইনগুলো থেকে পুরো বাক্য তৈরি:
    // আগের লাইন দাঁড়ি/প্রশ্ন/আশ্চর্য চিহ্ন ছাড়া শেষ হলে আর পরের লাইন সাথে সাথে এলে, দুটো একসাথে পড়া হবে
    private fun buildUnits(list: List<Cue>): List<SpeechUnit> {
        val out = ArrayList<SpeechUnit>()
        var cur: SpeechUnit? = null
        var count = 0
        for (i in list.indices) {
            val c = list[i]
            if (c.speech.isBlank()) {
                cur = null
                continue
            }
            val u = cur
            val canJoin = u != null && count < MAX_JOIN_CUES && !c.newSpeaker && !endsSentence(u.text) &&
                c.startMs - u.lastEndMs <= JOIN_GAP_MS &&
                u.text.length + c.speech.length <= MAX_JOIN_CHARS
            if (u != null && canJoin) {
                u.text = joinSpeech(u.text, c.speech)
                u.lastCue = i
                u.lastEndMs = max(u.lastEndMs, c.endMs)
                count++
            } else {
                val n = SpeechUnit(c.startMs, i, i, c.speech, c.endMs)
                out.add(n)
                cur = n
                count = 1
            }
        }
        for (u in out) u.text = finishSpeech(u.text)
        val kept = out.filter { it.text.isNotBlank() }
        for (k in kept.indices) {
            val u = kept[k]
            val n = kept.getOrNull(k + 1)
            u.nextStartMs = if (n != null) max(n.startMs, u.startMs + 300L) else Long.MAX_VALUE / 4L
            // বাক্য পড়া শেষে কতক্ষণ থামা হবে: পুরো বাক্য শেষ হলে একটু বেশি, মাঝপথে ভাঙলে একটু কম
            u.pauseMs = if (endsSentence(u.text)) {
                PAUSE_END_MS
            } else if (n != null && list.getOrNull(n.firstCue)?.newSpeaker == true) {
                PAUSE_SPEAKER_MS
            } else {
                PAUSE_MID_MS
            }
        }
        return kept
    }

    // ================= কণ্ঠ (Google TTS) =================

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsFailed = true
            showWarn("কণ্ঠ ইঞ্জিন চালু করা যায়নি। Play Store থেকে Speech Services by Google অ্যাপটি ইনস্টল বা আপডেট করুন।")
            return
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                outcomes[utteranceId] = true
                latches[utteranceId]?.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                markFailed(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                markFailed(utteranceId)
            }
        })
        ttsReady = true
        loadVoices()
        schedulePrep(nextSpeakIdx)
    }

    private fun markFailed(utteranceId: String?) {
        if (utteranceId == null) return
        outcomes[utteranceId] = false
        latches[utteranceId]?.countDown()
    }

    private fun isBangla(locale: Locale): Boolean {
        val l = locale.language.lowercase(Locale.US)
        return l == "bn" || l == "ben"
    }

    private fun loadVoices() {
        val t = tts ?: return
        val all: Set<Voice> = try {
            t.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val bnVoices = all.filter { v ->
            val features: Set<String> = v.features ?: emptySet()
            isBangla(v.locale) && !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val offline = bnVoices.filter { !it.isNetworkConnectionRequired }
        val usable = if (offline.isNotEmpty()) offline else bnVoices
        voiceList = usable.sortedWith(
            compareBy<Voice>(
                { if (it.locale.country.equals("IN", ignoreCase = true)) 0 else 1 },
                { if (it.isNetworkConnectionRequired) 1 else 0 },
                { -it.quality },
                { it.name }
            )
        )

        val labels = ArrayList<String>()
        var nIn = 0
        var nBd = 0
        var nOther = 0
        for (v in voiceList) {
            var label = when (v.locale.country.uppercase(Locale.US)) {
                "IN" -> {
                    nIn++
                    "বাংলা (ভারত) · কণ্ঠ " + bn(nIn.toString())
                }
                "BD" -> {
                    nBd++
                    "বাংলা (বাংলাদেশ) · কণ্ঠ " + bn(nBd.toString())
                }
                else -> {
                    nOther++
                    "বাংলা · কণ্ঠ " + bn(nOther.toString())
                }
            }
            if (v.isNetworkConnectionRequired) label += " (ইন্টারনেট লাগবে)"
            labels.add(label)
        }
        if (labels.isEmpty()) labels.add("বাংলা (ডিফল্ট)")

        val saved = prefs.getString("voice", null)
        var sel = voiceList.indexOfFirst { it.name == saved }
        if (sel < 0) sel = 0
        val newVoice = voiceList.getOrNull(sel)
        val changed = newVoice?.name != wantedVoice?.name
        wantedVoice = newVoice

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.textSize = 15f
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
        voiceSpinner.setSelection(sel)
        if (changed && cues.isNotEmpty()) regenerate()

        val hasIndia = voiceList.any { it.locale.country.equals("IN", ignoreCase = true) }
        if (!hasIndia) {
            showWarn("আপনার ফোনে বাংলা (ভারত) কণ্ঠ ডাউনলোড করা নেই। নিচের বোতাম চাপুন, তারপর Google-এর ভয়েস তালিকা থেকে Bengali (India) ডাউনলোড করে অ্যাপে ফিরে আসুন। (একবারই ইন্টারনেট লাগবে, এরপর সম্পূর্ণ অফলাইন)")
        } else {
            warnBox.visibility = View.GONE
        }
    }

    private fun openVoiceInstall() {
        val tries = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage("com.google.android.tts"),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        flash("সেটিংস খোলা যায়নি। Settings > Text-to-speech থেকে নিজে ডাউনলোড করুন।")
    }

    private fun cleanOldAudioFiles() {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".wav")) f.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun regenerate() {
        prepGen++
        cache.clear()
        schedulePrep(nextSpeakIdx)
    }

    // সামনের কয়েকটি লাইনের কণ্ঠ পেছনে পেছনে তৈরি করে রাখা
    private fun schedulePrep(from: Int) {
        if (!ttsReady) return
        val list = units
        if (list.isEmpty()) return
        wantFrom = from
        val gen = prepGen
        for (k in cache.keys) {
            if (k < from - 1 || k > from + AHEAD + 4) cache.remove(k)
        }
        val last = min(list.size - 1, from + AHEAD)
        for (i in from..last) {
            if (cache.containsKey(i)) continue
            val key = gen.toString() + ":" + i.toString()
            if (!inFlight.add(key)) continue
            try {
                executor.execute {
                    try {
                        prepareJob(i, gen, list)
                    } finally {
                        inFlight.remove(key)
                    }
                }
            } catch (e: Exception) {
                inFlight.remove(key)
            }
        }
    }

    private fun prepareJob(i: Int, gen: Int, list: List<SpeechUnit>) {
        if (gen != prepGen) return
        val from = wantFrom
        if (i < from - 1 || i > from + AHEAD + 2) return
        if (cache.containsKey(i)) return
        val unit = list.getOrNull(i) ?: return
        if (unit.text.isBlank()) {
            if (gen == prepGen) cache[i] = EMPTY_CLIP
            return
        }
        applyVoiceIfNeeded()
        var clip = synth(unit.text, BASE_RATE)
        if (clip != null && clip.ok) {
            // এই বাক্যের হাতে কতটা ভিডিও সময় আছে (পরের বাক্য শুরু পর্যন্ত), তার মধ্যে কথা আর বিরতি আঁটাতে হবে
            val window = unit.nextStartMs - unit.startMs
            if (window in 300L..600000L) {
                val need = (clip.durMs + unit.pauseMs).toFloat() / window.toFloat()
                // ব্যালেন্স: বাড়তি সময়ের অর্ধেক মেটাবে কণ্ঠ একটু দ্রুত পড়ে, বাকি অর্ধেক ভিডিও একটু ধীর হয়ে
                // যেমন ২০% বেশি সময় লাগলে: কণ্ঠ প্রায় ১০% দ্রুত, ভিডিও প্রায় ১০% ধীর
                val boost = min(VOICE_MAX, sqrt(need))
                if (boost > 1.04f) {
                    val faster = synth(unit.text, BASE_RATE * boost)
                    if (faster != null && faster.ok) clip = faster
                }
            }
        }
        if (clip == null) failCount++
        if (gen == prepGen) cache[i] = clip ?: EMPTY_CLIP
    }

    private fun applyVoiceIfNeeded() {
        val t = tts ?: return
        val v = wantedVoice
        val key = v?.name ?: "lang-bn-IN"
        if (key == appliedVoiceKey) return
        try {
            if (v != null) t.setVoice(v) else t.setLanguage(Locale.forLanguageTag("bn-IN"))
        } catch (e: Exception) {
        }
        appliedVoiceKey = key
    }

    private fun synth(text: String, rate: Float): Clip? {
        val t = tts ?: return null
        val id = "u" + SystemClock.elapsedRealtimeNanos().toString()
        val latch = CountDownLatch(1)
        latches[id] = latch
        val file = File(cacheDir, "$id.wav")
        try {
            t.setSpeechRate(rate)
            t.setPitch(1.0f)
            val r = t.synthesizeToFile(text, Bundle(), file, id)
            if (r != TextToSpeech.SUCCESS) return null
            val finished = latch.await(20L, TimeUnit.SECONDS)
            if (!finished || outcomes[id] != true) return null
            return readWav(file)
        } catch (e: Exception) {
            return null
        } finally {
            latches.remove(id)
            outcomes.remove(id)
            try {
                file.delete()
            } catch (e: Exception) {
            }
        }
    }

    // WAV ফাইল পড়ে শুরু ও শেষের নীরবতা কেটে আসল কথার মাপ বের করা
    private fun readWav(f: File): Clip? {
        if (!f.exists()) return null
        val all = f.readBytes()
        if (all.size < 44) return null
        if (String(all, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var channels = 1
        var rate = 22050
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        while (p + 8 <= all.size) {
            val id = String(all, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (id == "fmt " && p + 24 <= all.size) {
                channels = bb.getShort(p + 10).toInt()
                rate = bb.getInt(p + 12)
                bits = bb.getShort(p + 22).toInt()
            } else if (id == "data") {
                dataStart = p + 8
                dataLen = if (size <= 0 || dataStart + size > all.size) all.size - dataStart else size
                break
            }
            if (size < 0) break
            p += 8 + size + (size and 1)
        }
        if (dataStart < 0 || bits != 16 || channels < 1 || channels > 2 || rate < 4000) return null
        val frameBytes = 2 * channels
        val frames = dataLen / frameBytes
        if (frames <= 0) return EMPTY_CLIP
        // নরম শুরুর শব্দ (যেমন স, হ) যাতে কেটে না যায়, তাই সীমা কম রাখা হয়েছে
        val threshold = 300
        var first = -1
        for (i in 0 until frames) {
            if (abs(bb.getShort(dataStart + i * frameBytes).toInt()) > threshold) {
                first = i
                break
            }
        }
        if (first < 0) return EMPTY_CLIP
        var last = first
        for (i in frames - 1 downTo first) {
            if (abs(bb.getShort(dataStart + i * frameBytes).toInt()) > threshold) {
                last = i
                break
            }
        }
        val s0 = max(0, first - rate * 60 / 1000)
        val s1 = min(frames, last + 1 + rate * 120 / 1000)
        val realFrames = s1 - s0
        val realBytes = realFrames * frameBytes
        val out = ByteArray(max(realBytes, 8192))
        System.arraycopy(all, dataStart + s0 * frameBytes, out, 0, realBytes)

        // শুরু আর শেষে খুব ছোট ফেড, যাতে "টক" শব্দ না হয়
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val fadeIn = min(realFrames / 4, rate * 8 / 1000)
        val fadeOut = min(realFrames / 4, rate * 25 / 1000)
        if (fadeIn > 0) {
            for (fr in 0 until fadeIn) {
                val g = fr.toFloat() / fadeIn.toFloat()
                for (ch in 0 until channels) {
                    val at = (fr * channels + ch) * 2
                    ob.putShort(at, (ob.getShort(at) * g).toInt().toShort())
                }
            }
        }
        if (fadeOut > 0) {
            for (k in 0 until fadeOut) {
                val fr = realFrames - 1 - k
                val g = k.toFloat() / fadeOut.toFloat()
                for (ch in 0 until channels) {
                    val at = (fr * channels + ch) * 2
                    ob.putShort(at, (ob.getShort(at) * g).toInt().toShort())
                }
            }
        }
        return Clip(true, out, rate, channels, realFrames, realFrames * 1000L / rate)
    }

    private fun buildTrack(clip: Clip): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val mask = if (clip.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val fmt = AudioFormat.Builder()
                .setSampleRate(clip.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(mask)
                .build()
            val track = AudioTrack(attrs, fmt, clip.pcm.size, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                track.release()
                return null
            }
            track.write(clip.pcm, 0, clip.pcm.size)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.play()
            track
        } catch (e: Exception) {
            null
        }
    }

    private fun startSpeaking(idx: Int, clip: Clip) {
        val track = buildTrack(clip)
        cache.remove(idx)
        nextSpeakIdx = idx + 1
        waitStart = 0L
        if (track != null) {
            speaking = Speaking(idx, track, clip.frames, clip.sampleRate, 0, SystemClock.uptimeMillis())
        } else {
            lastVoiceEndAt = SystemClock.uptimeMillis()
            pauseNeeded = 0L
        }
        schedulePrep(nextSpeakIdx)
    }

    private fun finishSpeaking() {
        val sp = speaking ?: return
        speaking = null
        try {
            sp.track.stop()
        } catch (e: Exception) {
        }
        try {
            sp.track.release()
        } catch (e: Exception) {
        }
    }

    private fun advance() {
        nextSpeakIdx++
        waitStart = 0L
        schedulePrep(nextSpeakIdx)
    }

    // ভিডিওর কোনো জায়গায় লাফ দিলে কোন বাক্য থেকে পড়া শুরু হবে
    // বাক্যের মাঝখানে পড়লে সেটা বাদ দিয়ে পরেরটা থেকে শুরু, নাহলে পুরো বাক্য পড়তে ভিডিও প্রায় থেমে যেত
    private fun firstUnitFrom(list: List<SpeechUnit>, pos: Long): Int {
        for (k in list.indices) {
            val u = list[k]
            val span = max(0L, min(u.nextStartMs, u.lastEndMs) - u.startMs)
            val allow = min(700L, max(250L, span * 3L / 10L))
            if (pos <= u.startMs + allow) return k
        }
        return list.size
    }

    private fun resetSpeech(pos: Long) {
        finishSpeaking()
        nextSpeakIdx = firstUnitFrom(units, pos)
        waitStart = 0L
        lastVoiceEndAt = 0L
        pauseNeeded = 0L
        holdDuckUntil = 0L
        debtMs = 0f
        lastShownIdx = -2
        if (::player.isInitialized) setSpeedNow(1f)
        schedulePrep(nextSpeakIdx)
    }

    // ================= মূল সিঙ্ক ইঞ্জিন (প্রতি ৩০ মিলিসেকেন্ডে চলে) =================

    private fun findCueAt(list: List<Cue>, pos: Long): Int {
        var lo = 0
        var hi = list.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var k = ans
        while (k >= 0 && k >= ans - 3) {
            if (pos < list[k].endMs) return k
            k--
        }
        return -1
    }

    private fun updateSubtitleView(pos: Long) {
        val list = cues
        val idx = if (list.isEmpty()) -1 else findCueAt(list, pos)
        if (idx == lastShownIdx) return
        lastShownIdx = idx
        if (idx < 0 || !showSubs) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = list[idx].display
            subtitleView.visibility = View.VISIBLE
        }
    }

    private fun updateTimeUi(pos: Long) {
        val dur = player.duration
        if (dur > 0L) {
            val d = min(dur, Int.MAX_VALUE.toLong()).toInt()
            if (seekBar.max != d) seekBar.max = d
            if (fsSeek.max != d) fsSeek.max = d
            if (!userSeeking) {
                val p = min(pos, dur).toInt()
                val txt = formatTime(pos)
                seekBar.progress = p
                curTime.text = txt
                if (fsBarVisible) {
                    fsSeek.progress = p
                    fsCur.text = txt
                }
            }
            val dt = formatTime(dur)
            durTime.text = dt
            fsDur.text = dt
        }
    }

    private fun refreshStatus(now: Long) {
        if (now < statusMsgUntil) {
            statusText.text = statusMsg
            return
        }
        val list = cues
        val us = units
        val n = bn(list.size.toString())
        var text = when {
            player.mediaItemCount == 0 && list.isEmpty() -> "শুরু করতে ভিডিও আর SRT ফাইল বেছে নিন।"
            list.isEmpty() -> "এখন SRT ফাইল বেছে নিন।"
            player.mediaItemCount == 0 -> "এখন ভিডিও বেছে নিন।"
            ttsFailed -> "⚠ কণ্ঠ ইঞ্জিন চালু হয়নি, শুধু ভিডিও আর লেখা চলবে।"
            !ttsReady -> "কণ্ঠ ইঞ্জিন চালু হচ্ছে…"
            nextSpeakIdx >= us.size && speaking == null -> n + "টি সাবটাইটেল · সব লাইন পড়া শেষ"
            speaking != null || cache.containsKey(nextSpeakIdx) -> "✅ " + n + "টি সাবটাইটেল · কণ্ঠ প্রস্তুত"
            else -> "⏳ " + n + "টি সাবটাইটেল · কণ্ঠ তৈরি হচ্ছে…"
        }
        if (failCount > 0) text += NL + "⚠ কিছু লাইনের কণ্ঠ তৈরি করা যায়নি (" + bn(failCount.toString()) + "টি)"
        statusText.text = text
    }

    // এখন থেকে পরের বাংলা কথা শুরু হতে আনুমানিক কত সময় বাকি (আসল সময়, মিলিসেকেন্ড)
    private fun timeToNextVoice(nx: SpeechUnit?, pos: Long, now: Long): Long {
        if (nx == null) return Long.MAX_VALUE / 4L
        val vLeft = nx.startMs - LEAD_MS - pos
        val byVideo = if (vLeft <= 0L) 0L else (vLeft.toFloat() / max(curSpeed, 0.5f)).toLong()
        val pauseLeft = max(0L, lastVoiceEndAt + pauseNeeded - now)
        return max(byVideo, pauseLeft)
    }

    private fun tick() {
        if (!::player.isInitialized || released) return
        val now = SystemClock.uptimeMillis()
        val pos = player.currentPosition
        val dt = if (lastTickAt > 0L) (now - lastTickAt).coerceIn(0L, 100L) else 0L
        lastTickAt = now
        tickCount++
        updateSubtitleView(pos)
        if (tickCount % 5 == 0) updateTimeUi(pos)
        if (tickCount % 10 == 0) {
            refreshStatus(now)
            schedulePrep(nextSpeakIdx)
        }
        if (!player.isPlaying) return
        val list = units
        val voiceOn = ttsReady

        // ভিডিও ধীর চললে দেখতে সময় বেশি লাগে (ধার জমে), পরে সামান্য দ্রুত চললে সেই ধার শোধ হয়
        debtMs = (debtMs + dt.toFloat() * (1f - appliedSpeed)).coerceIn(0f, MAX_DEBT_MS)

        // ১. চলতি কণ্ঠ শেষ হয়েছে কিনা দেখা
        val sp0 = speaking
        if (sp0 != null) {
            val head = sp0.track.playbackHeadPosition
            if (head != sp0.lastHead) {
                sp0.lastHead = head
                sp0.lastMoveAt = now
            }
            if (head >= sp0.frames - 1 || now - sp0.lastMoveAt > 1500L) {
                val done = list.getOrNull(sp0.idx)
                finishSpeaking()
                lastVoiceEndAt = now
                var p = done?.pauseMs ?: PAUSE_MID_MS
                // কণ্ঠ আড়াই সেকেন্ডের বেশি পিছিয়ে পড়লে বিরতি ছোট করে তাড়াতাড়ি ধরে ফেলা
                val nu = list.getOrNull(nextSpeakIdx)
                if (nu != null && pos - nu.startMs > 2500L) p = min(p, 200L)
                pauseNeeded = p
                // পরের বাংলা কথা ২ সেকেন্ডের মধ্যেই এলে মাঝখানে আসল আওয়াজ ফিরবে না
                val gap = timeToNextVoice(nu, pos, now)
                holdDuckUntil = if (gap <= HOLD_GAP_MS) now + gap + 300L else 0L
            }
        }

        // ২. বিরতি শেষ হলে আর ভিডিও পরের বাক্যের সময়ে পৌঁছালে, পরের বাক্যের কণ্ঠ শুরু
        if (voiceOn && speaking == null && nextSpeakIdx < list.size) {
            val u = list[nextSpeakIdx]
            val clip = cache[nextSpeakIdx]
            if (clip != null && !clip.ok) {
                advance()
            } else if (clip != null) {
                val pauseDone = now - lastVoiceEndAt >= pauseNeeded
                if (pauseDone && pos >= u.startMs - LEAD_MS) startSpeaking(nextSpeakIdx, clip)
            } else if (pos >= u.startMs) {
                if (waitStart == 0L) waitStart = now
                if (now - waitStart > 4000L) advance()
            }
        }

        // ৩. গণিত: বাকি কথা আর তার পরের বিরতি শেষ হতে যত সময় লাগবে, ঠিক তত সময়ে ভিডিও পরের বাক্যে পৌঁছাবে
        //    ভিডিওর গতি = (পরের বাক্য পর্যন্ত বাকি ভিডিও সময়) / (বাকি কথা + বিরতির আসল সময়)
        //    প্রতি ৩০ মিলিসেকেন্ডে নতুন করে মাপা হয়, তাই ভুল জমে না
        var target = 1f
        var late = 0L
        var canSpeedUp = false
        if (voiceOn && list.isNotEmpty()) {
            val sp = speaking
            val nIdx = if (sp != null) sp.idx + 1 else nextSpeakIdx
            val nu = list.getOrNull(nIdx)
            val goal = if (nu != null) {
                nu.startMs - LEAD_MS
            } else {
                val d = player.duration
                if (d > 0L) d - 300L else Long.MAX_VALUE / 4L
            }
            val needReal = if (sp != null) {
                val remain = max(0, sp.frames - sp.lastHead).toLong() * 1000L / max(1, sp.sampleRate).toLong()
                remain + (list.getOrNull(sp.idx)?.pauseMs ?: PAUSE_MID_MS)
            } else {
                max(0L, lastVoiceEndAt + pauseNeeded - now)
            }
            val vLeft = goal - pos
            if (vLeft < 0L) late = -vLeft
            val waitingClip = sp == null && nu != null && !cache.containsKey(nIdx)
            canSpeedUp = nu != null && !waitingClip
            target = when {
                waitingClip -> if (vLeft > 700L) 1f else SPEED_FLOOR
                needReal <= 30L -> SPEED_UP_MAX
                vLeft <= 0L -> 0f
                else -> vLeft.toFloat() / needReal.toFloat()
            }
        }
        // ভিডিও কখনো থামবে না: সাধারণত সর্বনিম্ন ০.৭x, কণ্ঠ অনেক পিছিয়ে পড়লে অল্প সময়ের জন্য সর্বনিম্ন ০.৫৫x
        val behind = ((late - 500L).toFloat() / 1500f).coerceIn(0f, 1f)
        val floor = SPEED_FLOOR - (SPEED_FLOOR - SPEED_FLOOR_LATE) * behind
        // আগে ভিডিও ধীর হয়ে থাকলে, ছোট বাক্য বা ফাঁকা সময়ে সামান্য দ্রুত চালিয়ে পুষিয়ে নেওয়া
        // (উপরের হিসাবের কারণে এতেও দুই কণ্ঠের মাঝের বিরতি কাটা পড়ে না)
        val ceil = if (canSpeedUp && debtMs > 150f) SPEED_UP_MAX else 1f
        target = target.coerceIn(floor, ceil)
        // খুব সামান্য পার্থক্যে গতি বদলানো হবে না, এতে ভিডিও আরও মসৃণ চলে
        if (target > 0.97f && target < 1.03f) target = 1f

        // ৪. গতি হঠাৎ না বদলে ধীরে ধীরে বদলানো (দ্রুত থেকে স্বাভাবিকে ফেরাটা খুব নরমভাবে)
        curSpeed = if (target < curSpeed) {
            max(target, curSpeed - (if (curSpeed > 1.001f) 0.015f else 0.08f))
        } else {
            min(target, curSpeed + 0.02f)
        }
        if (abs(curSpeed - appliedSpeed) >= 0.02f || (curSpeed == target && curSpeed != appliedSpeed)) {
            player.playbackParameters = PlaybackParameters(curSpeed)
            appliedSpeed = curSpeed
        }

        // ৫. ভিডিওর আসল আওয়াজ
        updateVolume(list, voiceOn, pos, now, dt)
    }

    // ভিডিওর আসল আওয়াজ:
    // বাংলা পড়ার সময় প্রায় বন্ধ (১%), শুধু বাংলা কণ্ঠ শোনা যায়
    // বাংলা শুরুর একটু আগে নরমভাবে কমে, পড়া শেষ হলে খুব ধীরে ধীরে ফিরে আসে
    // দুই বাংলা কথার মাঝে ২ সেকেন্ডের কম ফাঁক হলে মাঝখানে আসল আওয়াজ ফেরে না
    private fun updateVolume(list: List<SpeechUnit>, voiceOn: Boolean, pos: Long, now: Long, dt: Long) {
        val nx = list.getOrNull(nextSpeakIdx)
        val nextUsable = nx != null && cache[nextSpeakIdx]?.ok != false
        val soon = voiceOn && speaking == null && nextUsable && timeToNextVoice(nx, pos, now) <= PRE_DUCK_MS
        val low = voiceOn && (speaking != null || now < holdDuckUntil || soon)
        val step = dt.toFloat()
        fadePos = if (low) {
            max(0f, fadePos - step / (if (speaking != null) 200f else FADE_OUT_MS))
        } else {
            min(1f, fadePos + step / FADE_IN_MS)
        }
        // আসল আওয়াজ বন্ধ/চালু করলে সেটাও নরমভাবে হবে
        val muteTarget = if (muteOriginal) 0f else 1f
        muteFade = if (muteFade < muteTarget) min(muteTarget, muteFade + step / 300f) else max(muteTarget, muteFade - step / 300f)
        // fadePos এর বর্গ নেওয়া হয়, এতে কানে আওয়াজ সমান তালে বাড়ে-কমে (ঝট করে লাফায় না)
        setGain(muteFade * (duckLevel + (1f - duckLevel) * fadePos * fadePos))
    }

    private fun setGain(g: Float) {
        curVol = g.coerceIn(0f, 1f)
        if (abs(curVol - appliedVol) > 0.001f || (curVol == 0f && appliedVol != 0f)) {
            player.volume = curVol
            appliedVol = curVol
        }
    }

    // ভিডিও থামানো অবস্থায় আওয়াজ বন্ধ/চালু করলে সাথে সাথে লাগানো
    private fun applyVolumeNow() {
        if (!::player.isInitialized) return
        muteFade = if (muteOriginal) 0f else 1f
        setGain(muteFade * (duckLevel + (1f - duckLevel) * fadePos * fadePos))
    }

    // ================= জীবনচক্র =================

    override fun onResume() {
        super.onResume()
        if (ttsReady) loadVoices()
        // সেটিংস থেকে অনুমতি দিয়ে ফিরে এলে সাথে সাথে তালিকা খোলা
        if (pendingPick != 0 && Build.VERSION.SDK_INT >= 30) {
            val k = pendingPick
            pendingPick = 0
            if (hasStorageAccess()) {
                openPicker(k)
            } else {
                flash("অনুমতি দেওয়া হয়নি। চাইলে তালিকার নিচের বোতাম দিয়ে নিজে খুঁজে নিন।")
            }
        }
    }

    // অ্যাপ সামনে এলে সিঙ্ক ইঞ্জিন চালু
    override fun onStart() {
        super.onStart()
        if (!released) {
            handler.removeCallbacks(tickRunnable)
            handler.post(tickRunnable)
        }
    }

    // হোম চাপলে বা অন্য অ্যাপে গেলে ভিডিও থামে আর সিঙ্ক ইঞ্জিন ঘুমায়, ব্যাটারি খরচ হয় না
    override fun onStop() {
        super.onStop()
        if (::player.isInitialized && !released) player.pause()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }
}
