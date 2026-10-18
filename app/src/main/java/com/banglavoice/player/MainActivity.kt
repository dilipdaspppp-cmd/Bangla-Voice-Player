package com.banglavoice.player

import android.app.Activity
import android.content.Intent
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

// একটি সাবটাইটেল লাইন
class Cue(val startMs: Long, var endMs: Long, var display: String, var speech: String) {
    // এই লাইনের কণ্ঠ ভিডিওর কোন সময়ের মধ্যে শেষ হওয়া উচিত (পরের লাইন শুরুর একটু আগে)
    var windowEnd: Long = 0L
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
        const val AHEAD = 6              // সামনের কয়টি লাইনের কণ্ঠ আগাম তৈরি থাকবে
        const val MARGIN_MS = 120L       // দুই লাইনের কণ্ঠের মাঝে ন্যূনতম বিরতি
        const val MIN_SPEED = 0.1f       // ভিডিও সর্বনিম্ন এই গতিতে চলবে, কখনো থামবে না
        const val WAIT_SPEED = 0.3f      // কণ্ঠ তৈরি না হওয়া পর্যন্ত ভিডিওর গতি
        const val MAX_BOOST = 1.12f      // কণ্ঠ সর্বোচ্চ এতটুকু দ্রুত হবে, যাতে স্বাভাবিক থাকে
        const val TICK_MS = 30L
        val EMPTY_CLIP = Clip(false, ByteArray(0), 0, 0, 0, 0L)
        val NL: String = Char(10).toString()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var player: ExoPlayer
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var cues: List<Cue> = emptyList()
    private val cache = ConcurrentHashMap<Int, Clip>()
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val outcomes = ConcurrentHashMap<String, Boolean>()
    @Volatile private var prepGen = 0
    @Volatile private var wantFrom = 0
    @Volatile private var baseRate = 1.0f
    @Volatile private var wantedVoice: Voice? = null
    @Volatile private var failCount = 0
    private var appliedVoiceKey: String? = null
    private var voiceList: List<Voice> = emptyList()

    private var speaking: Speaking? = null
    private var nextSpeakIdx = 0
    private var waitStart = 0L
    private var curSpeed = 1f
    private var appliedSpeed = 1f
    private var curVol = 1f
    private var appliedVol = 1f
    private var duckLevel = 0.1f
    private var duckHoldUntil = 0L
    private var tickCount = 0
    private var lastShownIdx = -2
    private var userSeeking = false
    private var isFullscreen = false
    private var videoW = 0
    private var videoH = 0
    private var statusMsg = ""
    private var statusMsgUntil = 0L

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
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var duckLabel: TextView
    private lateinit var duckSeek: SeekBar
    private lateinit var warnBox: LinearLayout
    private lateinit var warnText: TextView
    private lateinit var btnInstall: Button
    private lateinit var statusText: TextView

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
        baseRate = prefs.getFloat("rate", 1.0f).coerceIn(0.7f, 1.5f)
        duckLevel = prefs.getFloat("duck", 0.1f).coerceIn(0f, 0.5f)
        speedSeek.progress = ((baseRate - 0.7f) * 100f).roundToInt()
        duckSeek.progress = (duckLevel * 100f).roundToInt()
        updateSliderLabels()
        setupPlayer()
        setupControls()
        setupGestures()
        cleanOldAudioFiles()
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            runOnUiThread { onTtsInit(status) }
        }, "com.google.android.tts")
        restoreLastSession()
        handler.post(tickRunnable)
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
        speedLabel = findViewById<TextView>(R.id.speedLabel)!!
        speedSeek = findViewById<SeekBar>(R.id.speedSeek)!!
        duckLabel = findViewById<TextView>(R.id.duckLabel)!!
        duckSeek = findViewById<SeekBar>(R.id.duckSeek)!!
        warnBox = findViewById<LinearLayout>(R.id.warnBox)!!
        warnText = findViewById<TextView>(R.id.warnText)!!
        btnInstall = findViewById<Button>(R.id.btnInstall)!!
        statusText = findViewById<TextView>(R.id.statusText)!!
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
        voiceSpinner.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
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
        speedLabel.text = "কণ্ঠের গতি: " + bn(String.format(Locale.US, "%.2f", baseRate)) + "x"
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
            exitFull.visibility = View.VISIBLE
            root.setPadding(0, 0, 0, 0)
            subtitleView.textSize = 22f
            requestedOrientation = if (videoW > 0 && videoW < videoH) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            hideSystemBars()
        } else {
            header.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            exitFull.visibility = View.GONE
            subtitleView.textSize = 18f
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
        }
        root.requestApplyInsets()
        handler.post { applyLayout() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) hideSystemBars()
    }

    override fun onBackPressed() {
        if (isFullscreen) toggleFullscreen() else super.onBackPressed()
    }

    // ================= ভিডিও প্লেয়ার =================

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        player.setVideoSurfaceView(surfaceView)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                btnPlay.text = if (playWhenReady) "⏸  থামান" else "▶  চালান"
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

    private fun nextSubtitle() {
        val list = cues
        val pos = player.currentPosition
        val idx = list.indexOfFirst { it.startMs > pos + 50L }
        if (idx >= 0) seekToMs(list[idx].startMs)
    }

    private fun prevSubtitle() {
        val list = cues
        val pos = player.currentPosition
        val idx = list.indexOfLast { it.startMs < pos - 800L }
        if (idx >= 0) seekToMs(list[idx].startMs) else seekToMs(0L)
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePlay()
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
    }

    // ================= বোতাম ও স্লাইডার =================

    private fun setupControls() {
        btnPlay.setOnClickListener { togglePlay() }
        btnBack.setOnClickListener { seekToMs(player.currentPosition - 10000L) }
        btnFwd.setOnClickListener { seekToMs(player.currentPosition + 10000L) }
        btnFull.setOnClickListener { toggleFullscreen() }
        exitFull.setOnClickListener { toggleFullscreen() }
        btnVideo.setOnClickListener { pickFile(REQ_VIDEO, "video/*") }
        btnSrt.setOnClickListener { pickFile(REQ_SRT, "*/*") }
        btnInstall.setOnClickListener { openVoiceInstall() }

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

        speedSeek.setOnSeekBarChangeListener(SimpleSeek(
            { p, _ ->
                baseRate = 0.7f + p / 100f
                updateSliderLabels()
            },
            { },
            { _ ->
                prefs.edit().putFloat("rate", baseRate).apply()
                regenerate()
                flash("নতুন গতিতে কণ্ঠ তৈরি হচ্ছে…")
            }
        ))

        duckSeek.setOnSeekBarChangeListener(SimpleSeek(
            { p, _ ->
                duckLevel = p / 100f
                updateSliderLabels()
            },
            { },
            { _ -> prefs.edit().putFloat("duck", duckLevel).apply() }
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
        videoName.text = "🎬 " + displayName(uri)
        emptyHint.visibility = View.GONE
        resetSpeech(startAt)
    }

    private fun loadSrt(uri: Uri): Boolean {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val parsed = parseSrt(decodeText(bytes))
        if (parsed.isEmpty()) {
            flash("এই ফাইলে কোনো সাবটাইটেল পাওয়া যায়নি।")
            return false
        }
        prepGen++
        cache.clear()
        cues = parsed
        failCount = 0
        srtName.text = "📄 " + displayName(uri) + " · " + bn(parsed.size.toString()) + "টি লাইন"
        resetSpeech(if (player.mediaItemCount > 0) player.currentPosition else 0L)
        return true
    }

    private fun restoreLastSession() {
        val srt = prefs.getString("srt", null)
        if (srt != null) {
            try {
                if (!loadSrt(Uri.parse(srt))) prefs.edit().remove("srt").apply()
            } catch (e: Exception) {
                prefs.edit().remove("srt").apply()
            }
        }
        val video = prefs.getString("video", null)
        if (video != null) {
            try {
                val uri = Uri.parse(video)
                contentResolver.openInputStream(uri)?.close()
                loadVideo(uri, prefs.getLong("pos", 0L))
            } catch (e: Exception) {
                prefs.edit().remove("video").apply()
            }
        }
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

    private fun makeCue(start: Long, end: Long, lines: List<String>): Cue? {
        val shown = ArrayList<String>()
        val spoken = ArrayList<String>()
        for (raw in lines) {
            val noTags = removeBetween(removeBetween(raw, '<', '>'), '{', '}').trim()
            if (noTags.isNotEmpty()) shown.add(noTags)
            var s = removeBetween(noTags, '[', ']')
            s = s.filter { it != '♪' && it != '♫' && it != '#' && it != '*' }
            s = s.trim().trimStart('-', '–', '—').trim()
            if (s.isNotEmpty()) spoken.add(s)
        }
        val display = shown.joinToString(NL)
        val speech = collapseSpaces(spoken.joinToString(" "))
        if (display.isBlank() && speech.isBlank()) return null
        val e = if (end > start) end else start + 1500L
        return Cue(start, e, display, speech)
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
                last.speech = (last.speech + " " + c.speech).trim()
            } else {
                merged.add(c)
            }
        }

        // প্রতিটি লাইনের কণ্ঠ কখনের মধ্যে শেষ হওয়া উচিত তা হিসাব করা
        for (i in merged.indices) {
            val c = merged[i]
            c.windowEnd = if (i + 1 < merged.size) {
                max(merged[i + 1].startMs - MARGIN_MS, c.startMs + 300L)
            } else {
                Long.MAX_VALUE / 4L
            }
        }
        return merged
    }

    // ================= কণ্ঠ (Google TTS) =================

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
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
        val list = cues
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

    private fun prepareJob(i: Int, gen: Int, list: List<Cue>) {
        if (gen != prepGen) return
        val from = wantFrom
        if (i < from - 1 || i > from + AHEAD + 2) return
        if (cache.containsKey(i)) return
        val cue = list.getOrNull(i) ?: return
        if (cue.speech.isBlank()) {
            if (gen == prepGen) cache[i] = EMPTY_CLIP
            return
        }
        applyVoiceIfNeeded()
        var clip = synth(cue.speech, baseRate)
        if (clip != null && clip.ok) {
            // কণ্ঠ লম্বা হলে প্রথমে কণ্ঠ সামান্য দ্রুত করা হবে (সর্বোচ্চ ১২%), বাকিটা ভিডিও ধীর করে মেলানো হবে
            val window = cue.windowEnd - cue.startMs
            if (window in 301L..100000L && clip.durMs > window + 150L) {
                val factor = min(MAX_BOOST, clip.durMs.toFloat() / window.toFloat())
                if (factor > 1.03f) {
                    val faster = synth(cue.speech, baseRate * factor)
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
        val threshold = 500
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
        val s0 = max(0, first - rate * 40 / 1000)
        val s1 = min(frames, last + 1 + rate * 90 / 1000)
        val realFrames = s1 - s0
        val realBytes = realFrames * frameBytes
        val out = ByteArray(max(realBytes, 8192))
        System.arraycopy(all, dataStart + s0 * frameBytes, out, 0, realBytes)
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

    private fun resetSpeech(pos: Long) {
        finishSpeaking()
        val list = cues
        val idx = list.indexOfFirst { it.endMs > pos }
        nextSpeakIdx = if (idx < 0) list.size else idx
        waitStart = 0L
        duckHoldUntil = 0L
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
        if (idx < 0) {
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
            if (!userSeeking) {
                seekBar.progress = min(pos, dur).toInt()
                curTime.text = formatTime(pos)
            }
            durTime.text = formatTime(dur)
        }
    }

    private fun refreshStatus(now: Long) {
        if (now < statusMsgUntil) {
            statusText.text = statusMsg
            return
        }
        val list = cues
        val n = bn(list.size.toString())
        var text = when {
            player.mediaItemCount == 0 && list.isEmpty() -> "শুরু করতে ভিডিও আর SRT ফাইল বেছে নিন।"
            list.isEmpty() -> "এখন SRT ফাইল বেছে নিন।"
            player.mediaItemCount == 0 -> "এখন ভিডিও বেছে নিন।"
            !ttsReady -> "কণ্ঠ ইঞ্জিন চালু হচ্ছে…"
            nextSpeakIdx >= list.size && speaking == null -> n + "টি সাবটাইটেল · সব লাইন পড়া শেষ"
            speaking != null || cache.containsKey(nextSpeakIdx) -> "✅ " + n + "টি সাবটাইটেল · কণ্ঠ প্রস্তুত"
            else -> "⏳ " + n + "টি সাবটাইটেল · কণ্ঠ তৈরি হচ্ছে…"
        }
        if (failCount > 0) text += NL + "⚠ কিছু লাইনের কণ্ঠ তৈরি করা যায়নি (" + bn(failCount.toString()) + "টি)"
        statusText.text = text
    }

    private fun tick() {
        if (!::player.isInitialized) return
        val now = SystemClock.uptimeMillis()
        val pos = player.currentPosition
        tickCount++
        updateSubtitleView(pos)
        if (tickCount % 5 == 0) updateTimeUi(pos)
        if (tickCount % 10 == 0) {
            refreshStatus(now)
            schedulePrep(nextSpeakIdx)
        }
        if (!player.isPlaying) return
        val list = cues

        // ১. চলতি কণ্ঠ শেষ হয়েছে কিনা দেখা
        val sp0 = speaking
        if (sp0 != null) {
            val head = sp0.track.playbackHeadPosition
            if (head != sp0.lastHead) {
                sp0.lastHead = head
                sp0.lastMoveAt = now
            }
            if (head >= sp0.frames - 1 || now - sp0.lastMoveAt > 1500L) {
                finishSpeaking()
                duckHoldUntil = now + 350L
            }
        }

        // ২. সময় হলে পরের লাইনের কণ্ঠ শুরু করা
        if (speaking == null && nextSpeakIdx < list.size) {
            val cue = list[nextSpeakIdx]
            val clip = cache[nextSpeakIdx]
            if (clip != null && !clip.ok) {
                advance()
            } else if (clip != null) {
                if (pos >= cue.startMs - 60L) startSpeaking(nextSpeakIdx, clip)
            } else if (pos >= cue.startMs) {
                if (waitStart == 0L) waitStart = now
                if (now - waitStart > 3000L) advance()
            }
        }

        // ৩. গণিত: বাকি কথা শেষ হতে যত সময় লাগবে, ঠিক তত সময়ে ভিডিও পরের লাইনের কাছে পৌঁছাবে
        //    ভিডিওর গতি = (পরের লাইন পর্যন্ত বাকি ভিডিও সময়) / (বাকি কথার আসল সময়)
        //    প্রতি ৩০ মিলিসেকেন্ডে নতুন করে মাপা হয়, তাই ভুল জমে না
        var target = 1f
        val sp = speaking
        if (sp != null) {
            val cue = list.getOrNull(sp.idx)
            val remain = max(0, sp.frames - sp.lastHead).toLong() * 1000L / max(1, sp.sampleRate).toLong()
            if (cue != null && remain > 40L) {
                val vRemain = cue.windowEnd - pos
                target = if (vRemain <= 0L) MIN_SPEED else (vRemain.toFloat() / remain.toFloat()).coerceIn(MIN_SPEED, 1f)
            }
        } else if (nextSpeakIdx < list.size && !cache.containsKey(nextSpeakIdx)) {
            val until = list[nextSpeakIdx].startMs - pos
            target = if (until > 700L) 1f else if (until > 0L) 0.6f else WAIT_SPEED
        }

        // ৪. গতি হঠাৎ না বদলে ধীরে ধীরে বদলানো (কমানো দ্রুত, বাড়ানো নরমভাবে)
        curSpeed = if (target < curSpeed) max(target, curSpeed - 0.10f) else min(target, curSpeed + 0.035f)
        if (abs(curSpeed - appliedSpeed) >= 0.015f || (curSpeed == target && curSpeed != appliedSpeed)) {
            player.playbackParameters = PlaybackParameters(curSpeed)
            appliedSpeed = curSpeed
        }

        // ৫. কণ্ঠ চলার সময় ভিডিওর আওয়াজ ধীরে ধীরে কমানো, পরে ধীরে ধীরে ফেরানো
        val volTarget = if (speaking != null || now < duckHoldUntil) duckLevel else 1f
        curVol = if (volTarget < curVol) max(volTarget, curVol - 0.08f) else min(volTarget, curVol + 0.05f)
        if (abs(curVol - appliedVol) > 0.001f) {
            player.volume = curVol
            appliedVol = curVol
        }
    }

    // ================= জীবনচক্র =================

    override fun onResume() {
        super.onResume()
        if (ttsReady) loadVoices()
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized && player.mediaItemCount > 0) {
            prefs.edit().putLong("pos", player.currentPosition).apply()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        finishSpeaking()
        if (::player.isInitialized) player.release()
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
        super.onDestroy()
    }
}
