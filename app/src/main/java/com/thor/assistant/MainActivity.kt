package com.thor.assistant

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.view.KeyEvent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import android.content.pm.ApplicationInfo
import com.google.firebase.Firebase
import com.google.firebase.initialize
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var language: Spinner
    private lateinit var voiceStyle: Spinner
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var localStore: ThorLocalStore
    private lateinit var learningMemory: ThorLearningMemory
    private lateinit var bossProfile: ThorBossProfile
    private var bossSetupStatus: TextView? = null
    private var bossRecorder: MediaRecorder? = null
    private var bossSetupDialog: AlertDialog? = null
    private var pendingBossVoiceEnrollment = false
    private lateinit var offlineAi: ThorOfflineAi
    private lateinit var updateManager: ThorUpdateManager
    private var offlineAiButton: Button? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var firebaseReady = false
    private var wakeRecognizer: SpeechRecognizer? = null
    private var tapRecognizer: SpeechRecognizer? = null
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var wakeEnabled = false
    private var waitingForWakeCommand = false
    private var wakeButton: Button? = null

    private val languages = listOf("English", "Hindi", "Bengali")
    private val languageTags = listOf("en-IN", "hi-IN", "bn-IN")
    private val voiceStyles = listOf("Cinematic Deep", "Natural", "Crisp Assistant")

    private fun accentColor(): Int {
        val key = getSharedPreferences("thor_appearance", MODE_PRIVATE)
            .getString("accent", "champagne")
        return when (key) {
            "blue" -> Color.rgb(126, 177, 220)
            "purple" -> Color.rgb(177, 151, 218)
            "green" -> Color.rgb(139, 183, 154)
            "rose" -> Color.rgb(211, 155, 169)
            "gold" -> Color.rgb(215, 184, 121)
            else -> Color.rgb(213, 197, 164)
        }
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "Models",
            "AI provider & model",
            "Features",
            "Profile",
            "Appearance",
            "Language & voice",
            "About THOR",
            "Check for updates"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> installOfflineAi()
                    1 -> showAiProviderSettings()
                    2 -> showFeaturesDialog()
                    3 -> AlertDialog.Builder(this)
                        .setTitle("THOR Profile")
                        .setMessage("Current profile: ${bossProfile.bossName()}\\nProfile data is stored on this device.")
                        .setPositiveButton("OK", null)
                        .show().also { stylePremiumDialog(it) }
                    4 -> showThemeChooser()
                    5 -> showLanguageVoiceInfo()
                    6 -> AlertDialog.Builder(this)
                        .setTitle("About THOR")
                        .setMessage("Emblem engineered & developed by Aritra Adhikary and Deep Dikshit.")
                        .setPositiveButton("Close", null)
                        .show().also { stylePremiumDialog(it) }
                    7 -> checkForUpdates(silent = false)
                }
            }
            .setNegativeButton("Close", null)
            .show().also { stylePremiumDialog(it) }
    }

    private fun showFeaturesDialog() {
        val items = arrayOf(
            "Personalize appearance",
            "Offline AI",
            "Language and voice",
            if (wakeEnabled) "Turn wake word off" else "Turn wake word on",
            "Check for updates",
            "Help and commands"
        )
        AlertDialog.Builder(this)
            .setTitle("THOR Features")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeChooser()
                    1 -> installOfflineAi()
                    2 -> showLanguageVoiceInfo()
                    3 -> {
                        val button = wakeButton ?: makeButton("WAKE: OFF").also { wakeButton = it }
                        toggleWakeWord(button)
                    }
                    4 -> checkForUpdates(silent = false)
                    5 -> addMessage("THOR", helpText())
                }
            }
            .setNegativeButton("Close", null)
            .show().also { stylePremiumDialog(it) }
    }

    private fun showThemeChooser() {
        val labels = arrayOf(
            "Silver & Champagne",
            "Ocean Blue",
            "Royal Purple",
            "Emerald Green",
            "Rose"
        )
        val keys = arrayOf("champagne", "blue", "purple", "green", "rose")
        val current = getSharedPreferences("thor_appearance", MODE_PRIVATE)
            .getString("accent", "champagne")
        val selected = keys.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose your accent")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                getSharedPreferences("thor_appearance", MODE_PRIVATE)
                    .edit().putString("accent", keys[which]).apply()
                dialog.dismiss()
                val previousChat = if (::chat.isInitialized) chat.text.toString() else ""
                buildScreen()
                if (::chat.isInitialized) chat.text = previousChat
                Toast.makeText(this, labels[which] + " theme applied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show().also { stylePremiumDialog(it) }
    }

    private fun showLanguageVoiceInfo() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = "Language"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }, matchWrap())
        val languageChoice = Spinner(this)
        languageChoice.adapter = premiumSpinnerAdapter(languages)
        languageChoice.setPopupBackgroundDrawable(glassDrawable(0xFF1D1E22.toInt(), 0x66D7B879, 16f))
        languageChoice.setSelection(getSharedPreferences("thor_preferences", MODE_PRIVATE).getInt("language", 0).coerceIn(0, languages.lastIndex))
        panel.addView(languageChoice, matchWrap())
        panel.addView(TextView(this).apply {
            text = "Voice style"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }, matchWrap())
        val voiceChoice = Spinner(this)
        voiceChoice.adapter = premiumSpinnerAdapter(voiceStyles)
        voiceChoice.setPopupBackgroundDrawable(glassDrawable(0xFF1D1E22.toInt(), 0x66D7B879, 16f))
        voiceChoice.setSelection(getSharedPreferences("thor_voice", MODE_PRIVATE).getInt("style", 0).coerceIn(0, voiceStyles.lastIndex))
        panel.addView(voiceChoice, matchWrap())
        AlertDialog.Builder(this)
            .setTitle("Language & voice")
            .setView(panel)
            .setNeutralButton("Preview") { _, _ ->
                val lang = languageChoice.selectedItemPosition
                val sample = when (lang) {
                    1 -> "नमस्ते, मैं थॉर हूँ। मैं आपकी सहायता के लिए तैयार हूँ।"
                    2 -> "নমস্কার, আমি থর। আমি আপনাকে সাহায্য করতে প্রস্তুত।"
                    else -> "Hello. I am THOR. I am ready to help."
                }
                getSharedPreferences("thor_voice", MODE_PRIVATE).edit().putInt("style", voiceChoice.selectedItemPosition).apply()
                getSharedPreferences("thor_preferences", MODE_PRIVATE).edit().putInt("language", lang).apply()
                if (::language.isInitialized) language.setSelection(lang)
                if (::voiceStyle.isInitialized) voiceStyle.setSelection(voiceChoice.selectedItemPosition)
                speak(sample)
            }
            .setPositiveButton("Save") { _, _ ->
                val selectedLanguage = languageChoice.selectedItemPosition
                val selectedVoice = voiceChoice.selectedItemPosition
                getSharedPreferences("thor_preferences", MODE_PRIVATE).edit().putInt("language", selectedLanguage).apply()
                getSharedPreferences("thor_voice", MODE_PRIVATE).edit().putInt("style", selectedVoice).apply()
                if (::language.isInitialized) language.setSelection(selectedLanguage)
                if (::voiceStyle.isInitialized) voiceStyle.setSelection(selectedVoice)
                applyVoiceStyle()
                Toast.makeText(this, "Language and voice preferences saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show().also { stylePremiumDialog(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localStore = ThorLocalStore(this)
        learningMemory = ThorLearningMemory(this)
        bossProfile = ThorBossProfile(this)
        offlineAi = ThorOfflineAi(applicationContext)
        updateManager = ThorUpdateManager(this)
        schedulePeriodicUpdateChecks()
        requestUpdateNotificationPermission()
        initializeFirebase()
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("THOR", "Systems ready. Type a command or tap the microphone.")
        showBossSetupIfNeeded()
        if (intent?.getBooleanExtra("thor_open_update_prompt", false) == true) {
            Handler(Looper.getMainLooper()).post { checkForUpdates(silent = false) }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) checkForUpdates(silent = true)
            }, 12_000L)
        }
        if (offlineAi.isModelInstalled() || offlineAi.hasBundledModel()) {
            offlineAiButton?.text = "LOADING OFFLINE AI..."
            mainScope.launch {
                try {
                    offlineAi.initialize()
                    offlineAiButton?.text = "OFFLINE AI READY"
                    addMessage("THOR", "Offline AI loaded. I can now chat without internet.")
                } catch (error: Exception) {
                    offlineAiButton?.text = "RETRY OFFLINE AI"
                    addMessage("THOR", "Offline AI could not load: ${error.message ?: "model/runtime error"}")
                }
            }
        }
    }

    private fun schedulePeriodicUpdateChecks() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ThorUpdateCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "thor-ota-periodic-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("thor_open_update_prompt", false)) {
            checkForUpdates(silent = false)
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingPath = getSharedPreferences("thor_ota", MODE_PRIVATE)
            .getString("pending_install_path", null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            val pendingApk = java.io.File(pendingPath)
            if (pendingApk.isFile && pendingApk.length() > 0L) {
                updateManager.install(pendingApk)
            } else {
                getSharedPreferences("thor_ota", MODE_PRIVATE).edit()
                    .remove("pending_install_path").apply()
                addMessage("THOR", "The downloaded update was not found. Please check for updates again.")
            }
        }
    }

    private fun checkForUpdates(silent: Boolean) {
        mainScope.launch {
            try {
                val update = updateManager.checkForUpdate()
                if (update == null) {
                    if (!silent) addMessage("THOR", "THOR is already up to date.")
                    return@launch
                }
                val builder = AlertDialog.Builder(this@MainActivity)
                    .setTitle("THOR update available: ${update.versionName}")
                    .setMessage("${update.releaseNotes}\n\nDownload and install this update?")
                    .setPositiveButton("Download update") { _, _ ->
                        val progressDialog = android.app.ProgressDialog(this@MainActivity).apply {
                            setTitle("Downloading THOR ${update.versionName}")
                            setMessage("Downloading and verifying the update...")
                            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                            max = 100
                            progress = 0
                            setCancelable(false)
                            show()
                        }
                        mainScope.launch {
                            try {
                                addMessage("THOR", "Downloading THOR ${update.versionName}...")
                                val apk = updateManager.download(update) { percent ->
                                    runOnUiThread {
                                        if (progressDialog.isShowing) {
                                            progressDialog.progress = percent
                                            progressDialog.setMessage("Downloading update... $percent%")
                                        }
                                    }
                                }
                                progressDialog.dismiss()
                                addMessage("THOR", "Download verified. Checking package and signing identity.")
                                updateManager.install(apk)
                            } catch (error: Exception) {
                                if (progressDialog.isShowing) progressDialog.dismiss()
                                addMessage("THOR", "Update failed: ${error.message ?: error.javaClass.simpleName}")
                            }
                        }
                    }
                if (!update.mandatory) builder.setNegativeButton("Later", null)
                builder.setCancelable(!update.mandatory).show().also { stylePremiumDialog(it) }
            } catch (error: Exception) {
                if (!silent) {
                    addMessage("THOR", "Couldn't check for updates: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun showBossSetupIfNeeded() {
        if (bossProfile.isConfigured()) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        val nameInput = EditText(this).apply {
            hint = "What should THOR call you?"
            setSingleLine(true)
        }
        val explanation = TextView(this).apply {
            text = "Set up this installation's Boss profile. Record a short voice sample and THOR will save it privately on this device. Speaker matching is not active yet in this build."
            textSize = 14f
        }
        val status = TextView(this).apply {
            text = "Enter your name, then record your voice."
            textSize = 13f
        }
        bossSetupStatus = status
        val recordButton = Button(this).apply { text = "RECORD BOSS VOICE (5 SECONDS)" }
        val finishButton = Button(this).apply { text = "FINISH WITHOUT VOICE SAMPLE" }
        panel.addView(explanation)
        panel.addView(nameInput)
        panel.addView(recordButton)
        panel.addView(status)
        panel.addView(finishButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Welcome to THOR")
            .setView(panel)
            .setCancelable(false)
            .create()
        bossSetupDialog = dialog

        recordButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                nameInput.error = "Enter the Boss name first"
                return@setOnClickListener
            }
            bossProfile.saveBossName(name)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingBossVoiceEnrollment = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1004)
            } else {
                startBossVoiceEnrollment()
            }
        }
        finishButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                nameInput.error = "Enter a name to continue"
                return@setOnClickListener
            }
            bossProfile.saveBossName(name)
            bossProfile.finishSetup()
            dialog.dismiss()
            addMessage("THOR", "Welcome, " + bossProfile.bossName() + ". Your Boss profile is saved on this device. Voice matching is not enabled yet.")
            speak("Welcome, Boss.")
        }
        dialog.show()
        stylePremiumDialog(dialog)
    }

    private fun startBossVoiceEnrollment() {
        val sample = bossProfile.voiceSampleFile()
        try {
            bossRecorder?.release()
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            bossRecorder = recorder
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16000)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setOutputFile(sample.absolutePath)
            recorder.prepare()
            recorder.start()
            bossSetupStatus?.text = "Recording... speak naturally for five seconds."
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    bossRecorder?.stop()
                    bossRecorder?.release()
                    bossRecorder = null
                    bossProfile.finishSetup()
                    bossSetupDialog?.dismiss()
                    addMessage("THOR", "Voice sample saved privately on this device. Welcome, " + bossProfile.bossName() + ". Speaker matching still needs its verification model before I can identify voices.")
                    speak("Welcome, Boss.")
                } catch (error: Exception) {
                    bossRecorder?.release()
                    bossRecorder = null
                    sample.delete()
                    bossSetupStatus?.text = "Recording failed. Please try again or finish without a voice sample."
                    android.util.Log.e("THOR_BOSS", "Voice enrollment recording failed", error)
                }
            }, 5_000L)
        } catch (error: Exception) {
            bossRecorder?.release()
            bossRecorder = null
            bossSetupStatus?.text = "Could not start recording: " + (error.message ?: "microphone unavailable") + ". Try again or finish without a sample."
        }
    }

    private fun buildScreen() {
        val ink = Color.rgb(15, 16, 19)
        val panel = Color.rgb(29, 30, 34)
        val muted = Color.rgb(158, 158, 162)
        val ivory = Color.rgb(245, 242, 235)
        val gold = Color.rgb(215, 184, 121)
        val accent = accentColor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(13, 14, 17), Color.rgb(24, 24, 27), Color.rgb(12, 13, 16))
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "THOR"
            textSize = 27f
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ivory)
        }, matchWrap())
        brand.addView(TextView(this).apply {
            text = "YOUR PERSONAL AI"
            textSize = 9f
            letterSpacing = 0.22f
            setTextColor(muted)
        }, matchWrap())
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = makeButton("⚙")
        settings.textSize = 20f
        settings.background = glassRipple(0x332F3034, 0x55D7B879, 24f)
        header.addView(settings, LinearLayout.LayoutParams(dp(46), dp(46)))
        settings.setOnClickListener { showSettingsDialog() }
        root.addView(header, matchWrap())

        val welcome = TextView(this).apply {
            text = "Good to see you"
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(ivory)
            setPadding(dp(2), 0, 0, dp(3))
        }
        root.addView(welcome, matchWrap())
        root.addView(TextView(this).apply {
            text = "What would you like me to do?"
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(14))
        }, matchWrap())

        val content = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(13))
            background = glassDrawable(0xE51F2024.toInt(), 0x66D7B879, 30f)
            elevation = dp(4).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = "ARC REACTOR"
            textSize = 10f
            letterSpacing = 0.26f
            gravity = Gravity.CENTER
            setTextColor(gold)
        }, matchWrap())
        hero.addView(ThorHudView(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(188)
        ))
        hero.addView(TextView(this).apply {
            text = "TAP TO TALK WITH THOR"
            textSize = 11f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(ivory)
            setPadding(0, dp(2), 0, dp(7))
        }, matchWrap())
        // Compact creator seal retained from the approved THOR emblem treatment.
        val creatorMark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(8))
        }
        creatorMark.addView(TextView(this).apply {
            text = "A  •  D"
            textSize = 9f
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setTextColor(gold)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x221A1916)
                setStroke(dp(1), 0x99D7B879.toInt())
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(9) })
        val creatorCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        creatorCopy.addView(TextView(this).apply {
            text = "ENGINEERED & DEVELOPED BY"
            textSize = 7f
            letterSpacing = 0.13f
            setTextColor(Color.rgb(151, 145, 132))
        }, matchWrap())
        creatorCopy.addView(TextView(this).apply {
            text = "ARITRA ADHIKARY  •  DEEP DIKSHIT"
            textSize = 9f
            letterSpacing = 0.035f
            setTextColor(Color.rgb(220, 202, 166))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, matchWrap())
        creatorMark.addView(creatorCopy, matchWrap())
        hero.addView(creatorMark, matchWrap())
        hero.setOnClickListener { startVoiceInput() }
        column.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glassDrawable(0xAA25262A.toInt(), 0x44D7B879, 20f)
        }
        val statusText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText.addView(TextView(this).apply {
            text = "THOR IS READY"
            textSize = 11f
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(ivory)
        }, matchWrap())
        statusText.addView(TextView(this).apply {
            text = "Your assistant is standing by"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        }, matchWrap())
        statusCard.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(TextView(this).apply {
            text = "●"
            textSize = 15f
            setTextColor(Color.rgb(151, 196, 157))
        })
        column.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val sectionTitle = TextView(this).apply {
            text = "QUICK ACCESS"
            textSize = 10f
            letterSpacing = 0.20f
            setTextColor(Color.rgb(183, 177, 165))
            setPadding(dp(2), 0, 0, dp(10))
        }
        column.addView(sectionTitle, matchWrap())

        val tileRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun tileRow(
            left: Triple<String, String, () -> Unit>,
            right: Triple<String, String, () -> Unit>
        ) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeHomeTile(left.first, left.second, left.third), LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(6); bottomMargin = dp(10) })
            row.addView(makeHomeTile(right.first, right.second, right.third), LinearLayout.LayoutParams(0, dp(104), 1f).apply { leftMargin = dp(6); bottomMargin = dp(10) })
            tileRows.addView(row, matchWrap())
        }
        tileRow(Triple("◉", "Chat", { showChatDialog() }), Triple("⌁", "Voice", { startVoiceInput() }))
        tileRow(Triple("✧", "Offline AI", { installOfflineAi() }), Triple("▦", "Tools & features", { showFeaturesDialog() }))
        tileRow(Triple("↻", "App updates", { checkForUpdates(silent = false) }), Triple("文", "Language & voice", { showLanguageVoiceInfo() }))
        column.addView(tileRows, matchWrap())

        val footer = TextView(this).apply {
            text = "A smarter way to get things done."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(113, 113, 118))
            setPadding(0, dp(5), 0, dp(10))
        }
        column.addView(footer, matchWrap())
        content.addView(column)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(7), dp(5), dp(5))
            background = glassDrawable(0xE51E1F23.toInt(), 0x44D7B879, 24f)
        }
        fun navItem(label: String, glyph: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            item.addView(TextView(this).apply {
                text = glyph
                textSize = 23f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (label == "Home") gold else Color.rgb(218, 216, 210))
            }, matchWrap())
            item.addView(TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (label == "Home") ivory else Color.rgb(190, 188, 182))
            }, matchWrap())
            nav.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        navItem("Home", "⌂") { }
        navItem("Chat", "◉") { showChatDialog() }
        navItem("Tools", "▦") { showFeaturesDialog() }
        navItem("Settings", "⚙") { showSettingsDialog() }
        root.addView(nav, matchWrap())

        // Keep a lightweight backing transcript view so THOR can append messages before Chat is opened.
        chat = TextView(this).apply {
            textSize = 14f
            setTextColor(ivory)
        }
        input = EditText(this)
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages)
            setSelection(getSharedPreferences("thor_preferences", MODE_PRIVATE).getInt("language", 0).coerceIn(0, languages.lastIndex))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    getSharedPreferences("thor_preferences", MODE_PRIVATE).edit().putInt("language", position).apply()
                }
            }
        }
        voiceStyle = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voiceStyles)
            setSelection(getSharedPreferences("thor_voice", MODE_PRIVATE).getInt("style", 0).coerceIn(0, voiceStyles.lastIndex))
        }
        wakeButton = makeButton(if (wakeEnabled) "WAKE: ON" else "WAKE: OFF")
        offlineAiButton = makeButton("Install offline AI")
        setContentView(root)
    }

    private fun makeHomeTile(glyph: String, label: String, action: () -> Unit): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(12), dp(12))
            background = glassDrawable(0xB52B2C30.toInt(), 0x44D7B879, 22f)
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            setOnClickListener { action() }
        }
        tile.addView(TextView(this).apply {
            text = glyph
            textSize = 22f
            setTextColor(accentColor())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        tile.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Color.rgb(241, 239, 233))
            setPadding(0, dp(5), 0, 0)
        }, matchWrap())
        return tile
    }

    private fun showChatDialog() {
        val previousChat = chat
        val previousInput = input
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(24, 25, 29), Color.rgb(14, 15, 18))
            )
        }
        val transcript = TextView(this).apply {
            text = previousChat.text
            textSize = 14f
            setTextColor(Color.rgb(239, 238, 233))
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        chat = transcript
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(transcript)
        }
        dialogContent.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = glassDrawable(0xCC303238.toInt(), accentColor(), 24f)
        }
        val messageInput = EditText(this).apply {
            hint = "Message THOR..."
            setHintTextColor(Color.rgb(145, 145, 150))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = null
            setPadding(dp(10), dp(8), dp(8), dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        input = messageInput
        composer.addView(messageInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        val send = makeButton("Send")
        send.setOnClickListener {
            val command = messageInput.text.toString().trim()
            if (command.isNotEmpty()) {
                messageInput.setText("")
                handleCommand(command)
            }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(76), dp(42)))
        dialogContent.addView(composer, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Chat with THOR")
            .setView(dialogContent)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            previousChat.text = transcript.text
            chat = previousChat
            input = previousInput
        }
        dialog.show()
        stylePremiumDialog(dialog)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    /**
     * Gives every native THOR dialog a contemporary charcoal / champagne treatment.
     * Existing dialog actions remain unchanged.
     */
    private fun stylePremiumDialog(dialog: AlertDialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.68f)
        val surface = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(39, 40, 45), Color.rgb(20, 21, 25))
        ).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x66D7B879)
        }
        window.decorView.background = surface
        window.decorView.setPadding(dp(8), dp(5), dp(8), dp(5))

        val ivory = Color.rgb(245, 242, 235)
        val muted = Color.rgb(190, 188, 182)
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) {
            dialog.findViewById<TextView>(titleId)?.apply {
                setTextColor(ivory)
                textSize = 19f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(muted)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        listOf(android.R.id.button1, android.R.id.button2, android.R.id.button3).forEach { id ->
            dialog.findViewById<Button>(id)?.apply {
                setTextColor(accentColor())
                textSize = 13f
                isAllCaps = false
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                background = glassRipple(0x332F3034, 0x66D7B879, 18f)
            }
        }
        dialog.listView?.apply {
            divider = android.graphics.drawable.ColorDrawable(0x22FFFFFF)
            dividerHeight = dp(1)
            setBackgroundColor(Color.TRANSPARENT)
        }
        window.setLayout((resources.displayMetrics.widthPixels * 0.91f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun premiumSpinnerAdapter(options: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            private fun row(position: Int, convertView: android.view.View?, dropdown: Boolean): TextView {
                val view = (convertView as? TextView) ?: TextView(this@MainActivity)
                view.text = getItem(position).orEmpty()
                view.textSize = 14f
                view.setTextColor(if (dropdown) Color.rgb(245, 242, 235) else Color.rgb(230, 225, 215))
                view.setPadding(dp(14), dp(if (dropdown) 12 else 10), dp(14), dp(if (dropdown) 12 else 10))
                view.gravity = Gravity.CENTER_VERTICAL
                view.setBackgroundColor(if (dropdown) Color.rgb(29, 30, 34) else Color.TRANSPARENT)
                return view
            }

            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                row(position, convertView, false)

            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                row(position, convertView, true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun glassDrawable(
        fill: Int = 0xCC1B2A3B.toInt(),
        stroke: Int = 0x557BD9F4,
        radiusDp: Float = 22f
    ): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun glassRipple(
        fill: Int = 0xCC1B2A3B.toInt(),
        ripple: Int = 0x557BD9F4,
        radiusDp: Float = 22f
    ): android.graphics.drawable.Drawable {
        val mask = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ripple),
            glassDrawable(fill, 0x887BD9F4.toInt(), radiusDp),
            mask
        )
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun weightedButton() = LinearLayout.LayoutParams(
        0, dp(42), 1f
    ).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }

    private fun makeButton(label: String) = Button(this).apply {
        text = label
        textSize = 10f
        letterSpacing = 0.04f
        isAllCaps = false
        minHeight = dp(42)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(Color.rgb(242, 240, 234))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = glassRipple(0xCC303238.toInt(), accentColor(), 22f)
        stateListAnimator = null
        elevation = dp(2).toFloat()
    }

    private fun installOfflineAi() {
        val button = offlineAiButton ?: return
        if (offlineAi.isReady()) {
            addMessage("THOR", "Offline AI is already ready.")
            return
        }
        button.isEnabled = false
        mainScope.launch {
            try {
                if (!offlineAi.isModelInstalled() && !offlineAi.hasBundledModel()) {
                    addMessage("THOR", "Downloading the offline language model (~586 MB). Wi-Fi is recommended. Keep THOR open until it finishes.")
                    offlineAi.downloadModel { downloaded, total ->
                        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread { button.text = "DOWNLOADING $percent%" }
                    }
                }
                button.text = "LOADING OFFLINE AI..."
                addMessage("THOR", "Preparing the offline model and initializing the AI; first load may take a little while.")
                offlineAi.initialize()
                button.text = "OFFLINE AI READY"
                addMessage("THOR", "Offline AI is ready. English, Hindi, Bengali, and casual mixed-language chat are supported.")
            } catch (error: Exception) {
                button.text = if (offlineAi.isModelInstalled()) "RETRY OFFLINE AI" else "INSTALL OFFLINE AI (~586 MB)"
                addMessage("THOR", "Offline AI setup failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun askLocalOrFallback(prompt: String) {
        if (!offlineAi.isModelInstalled() && !offlineAi.hasBundledModel()) {
            val fallback = offlineReply(prompt) +
                "\\n\\nInstall the offline AI model using the button near the top of THOR to enable open-ended offline chat."
            addMessage("THOR", fallback)
            speak(fallback)
            return
        }
        mainScope.launch {
            try {
                if (!offlineAi.isReady()) {
                    addMessage("THOR", "Loading the offline AI...")
                    offlineAi.initialize()
                }
                val answer = offlineAi.ask(learningMemory.contextFor(prompt))
                addMessage("THOR", answer)
                speak(answer)
            } catch (error: Exception) {
                val fallback = offlineReply(prompt)
                addMessage("THOR", "Offline AI error: ${error.message ?: "could not generate a reply"}. $fallback")
                speak(fallback)
            }
        }
    }

    private fun startVoiceInput() {
        // Use Android's recognition service directly from THOR. This does not launch
        // the external Google microphone activity or display its separate popup.
        stopWakeWord()
        wakeButton?.text = "WAKE: OFF"

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("THOR", "No compatible speech-recognition service is installed or enabled on this device. Enable the device speech service and try again.")
            return
        }

        try {
            stopTapRecognition()
            tapRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        addMessage("THOR", "Listening. Speak now.")
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        android.util.Log.e("THOR_MIC", "Speech recognition error=$error")
                        stopTapRecognition()
                        addMessage("THOR", speechErrorMessage(error))
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        stopTapRecognition()
                        if (!spoken.isNullOrBlank()) handleCommand(spoken)
                        else addMessage("THOR", "I didn't catch that. Please try again.")
                    }
                })
            }

            val languageTag = languageTags.getOrElse(language.selectedItemPosition) { "en-IN" }
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Do not force offline mode: the installed recognition service chooses
                // its available engine and language pack, as in the earlier working build.
            }
            tapRecognizer?.startListening(request)
        } catch (error: Exception) {
            android.util.Log.e("THOR_MIC", "Could not start in-app speech recognition", error)
            stopTapRecognition()
            addMessage("THOR", "THOR could not start the microphone: ${error.message ?: error.javaClass.simpleName}. Check microphone permission and close other apps using the microphone.")
        }
    }

    private fun speechErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO ->
            "The speech service could not access microphone audio. Check Android's microphone privacy switch and close other apps using the mic."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Android denied microphone access. Allow Microphone in THOR's App info → Permissions."
        SpeechRecognizer.ERROR_CLIENT ->
            "The speech service stopped unexpectedly. Close and reopen THOR, then retry."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER ->
            "The speech service is unavailable. Check internet access or the installed speech service."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The speech service does not support the selected language. Try another language or install its speech pack."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Another app or recognition session is using the speech service. Wait a moment and retry."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "I couldn't hear clear speech. Please try again."
        else -> "Speech recognition failed (error $error). Check the device speech service and microphone permission."
    }

    private fun stopTapRecognition() {
        try { tapRecognizer?.cancel() } catch (_: Exception) {}
        try { tapRecognizer?.destroy() } catch (_: Exception) {}
        tapRecognizer = null
    }

    private fun handleCommand(raw: String) {
        addMessage("YOU", raw)
        val command = raw.trim()
        val lower = command.lowercase(Locale.ROOT)

        when {
            learningMemory.parseTeaching(command)?.let { learned ->
                addMessage("THOR", learned)
                speak(learned)
            } != null -> Unit

            lower in listOf("what have you learned", "show learned memory", "show what you learned", "তুমি কী শিখেছ", "क्या सीखा") -> {
                val entries = learningMemory.all()
                addMessage("THOR", if (entries.isEmpty()) "I haven't saved any lessons yet." else
                    entries.mapIndexed { index, entry -> "${index + 1}. ${entry.first} — ${entry.second}" }.joinToString("\\n"))
            }

            lower.startsWith("forget learning ") -> {
                val term = command.substringAfter("forget learning ", "").trim()
                val removed = learningMemory.forget(term)
                addMessage("THOR", if (removed) "I removed that lesson from learned memory." else "I couldn't find a saved lesson matching that.")
            }

            lower in listOf("clear learned memory", "forget everything you learned") -> {
                learningMemory.clear()
                addMessage("THOR", "Learned memory cleared.")
            }

            // Natural compound command: "open MX Player and play/resume music".
            Regex("""^(?:hey\s+)?thor[, ]*(?:please\s+)?(?:open|launch)\s+(.+?)\s+and\s+(?:then\s+)?(play|resume)(?:\s+the)?(?:\s+music)?$""", RegexOption.IGNORE_CASE)
                .find(command)?.let { match ->
                    openAppAndControlMedia(match.groupValues[1].trim(), match.groupValues[2].lowercase(Locale.ROOT))
                } != null -> Unit

            lower.contains("youtube") && lower.contains("download") &&
                (lower.contains("5th") || lower.contains("fifth")) && lower.contains("play") ->
                startYoutubeFifthDownloadNavigation()

            lower in listOf("help", "what can you do", "commands", "मदद", "সাহায্য") ->
                addMessage("THOR", helpText())

            lower.startsWith("ask ") || lower.startsWith("chat ") -> {
                val prompt = command.substringAfter(' ').trim()
                if (prompt.isNotBlank()) askGemini(prompt)
                else addMessage("THOR", "Type a question after 'ask'.")
            }

            lower.startsWith("note ") || lower.startsWith("याद ") || lower.startsWith("নোট ") -> {
                val text = command.substringAfter(' ').trim()
                if (text.isNotBlank()) {
                    localStore.addNote(text)
                    respond("Note saved.", "नोट सेव हो गया।", "নোট সংরক্ষণ করা হয়েছে।")
                } else addMessage("THOR", "Write something after 'note'.")
            }

            lower == "notes" || lower == "show notes" || lower == "मेरे नोट" || lower == "নোট দেখাও" -> {
                val notes = localStore.notes()
                addMessage("THOR", if (notes.isEmpty()) "You have no saved notes." else
                    notes.mapIndexed { index, note -> "${index + 1}. $note" }.joinToString("\n"))
            }

            lower.startsWith("todo ") || lower.startsWith("task ") -> {
                val text = command.substringAfter(' ').trim()
                if (text.isNotBlank()) {
                    localStore.addTask(text)
                    respond("Task added.", "काम जोड़ दिया गया।", "কাজ যোগ করা হয়েছে।")
                } else addMessage("THOR", "Write a task after 'todo'.")
            }

            lower == "tasks" || lower == "show tasks" || lower == "मेरे काम" || lower == "কাজ দেখাও" -> {
                val tasks = localStore.tasks()
                addMessage("THOR", if (tasks.isEmpty()) "Your task list is empty." else
                    tasks.mapIndexed { index, task -> "${index + 1}. [${if (task.second) "DONE" else "TODO"}] ${task.first}" }.joinToString("\n"))
            }

            lower.startsWith("done ") || lower.startsWith("complete task ") -> {
                val number = command.substringAfterLast(' ').toIntOrNull()
                if (number != null && localStore.completeTask(number - 1)) {
                    respond("Task marked complete.", "काम पूरा हुआ।", "কাজ সম্পন্ন হয়েছে।")
                } else addMessage("THOR", "Use 'done 1' with a valid task number.")
            }

            lower.contains("time") || lower.contains("समय") || lower.contains("সময়") -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                respond("The time is $time.", "समय $time है।", "এখন সময় $time।")
            }

            lower.contains("date") || lower.contains("तारीख") || lower.contains("তারিখ") -> {
                val date = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
                respond("Today's date is $date.", "आज की तारीख $date है।", "আজকের তারিখ $date।")
            }

            lower in listOf("wifi settings", "open wifi", "ওয়াইফাই সেটিংস") ->
                launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings.")
            lower in listOf("bluetooth settings", "open bluetooth", "ব্লুটুথ সেটিংস") ->
                launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings.")
            lower in listOf("display settings", "open display settings") ->
                launchIntent(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Opening display settings.")
            lower in listOf("battery settings", "open battery settings") ->
                launchIntent(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "Opening battery settings.")
            lower == "open settings" || lower.contains("settings kholo") ||
                lower.contains("सेटिंग") || lower.contains("সেটিংস") -> {
                launchIntent(Intent(Settings.ACTION_SETTINGS), "Opening Settings.")
            }

            lower.startsWith("call ") || lower.startsWith("dial ") ||
                lower.startsWith("कॉल ") || lower.startsWith("কল ") -> {
                val number = command.filter { it.isDigit() || it == '+' }
                if (number.isNotBlank()) {
                    launchIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                        "Opening the dialer. Review the number and tap call yourself.")
                } else {
                    addMessage("THOR", "Please include a phone number, for example: call 9876543210.")
                }
            }

            lower.startsWith("sms ") || lower.startsWith("message ") -> {
                val payload = command.substringAfter(' ').trim()
                val number = payload.takeWhile { it.isDigit() || it == '+' }
                val body = payload.drop(number.length).trim()
                if (number.isNotBlank()) {
                    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        if (body.isNotBlank()) putExtra("sms_body", body)
                    }
                    launchIntent(sms, "Opening SMS. Review the number and message before sending.")
                } else addMessage("THOR", "Use: sms 9876543210 Hello there")
            }

            
            lower.startsWith("alarm ") || lower.startsWith("remind ") ||
            lower.startsWith("reminder ") || lower.startsWith("मुझे याद ") ||
            lower.startsWith("মনে করিয়ে ") -> scheduleReminder(command)

            lower in listOf("pause music", "music pause", "গান থামাও") ->
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Sent pause to the active music player.")
            lower in listOf("resume music", "continue music") ->
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sent play to the active music player.")
            lower in listOf("next song", "next track", "পরের গান") ->
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to the next track.")
            lower in listOf("previous song", "previous track", "আগের গান") ->
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Returned to the previous track.")
            lower in listOf("volume up", "ভলিউম বাড়াও") -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                addMessage("THOR", "Media volume increased.")
            }
            lower in listOf("volume down", "ভলিউম কমাও") -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                addMessage("THOR", "Media volume decreased.")
            }

            lower == "play music" || lower == "open music" ||
                lower == "গান চালাও" || lower == "म्यूजिक चलाओ" -> {
                val candidates = listOf("YouTube Music", "Spotify", "Music")
                val opened = candidates.any { candidate ->
                    val wanted = candidate.lowercase(Locale.ROOT)
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull {
                        it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(wanted)
                    }
                    val launch = match?.let {
                        packageManager.getLaunchIntentForPackage(it.activityInfo.packageName)
                    }
                    if (launch != null) {
                        launchIntent(launch, "Opening ${match.loadLabel(packageManager)}.")
                        true
                    } else false
                }
                if (!opened) addMessage("THOR", "No supported music app was found. Install a music app or say: play followed by a song name.")
            }

            lower.startsWith("play ") || lower.startsWith("গান ") || lower.startsWith("बजाओ ") -> {
                val query = command.substringAfter(' ').trim()
                if (query.isNotBlank()) {
                    val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                    launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Searching for $query. Choose a result to play.")
                } else addMessage("THOR", "Say: play followed by a song name.")
            }

            lower.startsWith("set alarm ") ||
lower.startsWith("अलार्म ") ||
lower.startsWith("অ্যালার্ম ") -> {

    val match = Regex("""(\d{1,2})[:.](\d{2})""")
        .find(command)

    if (match != null) {
        val hour = match.groupValues[1].toIntOrNull()
        val minute = match.groupValues[2].toIntOrNull()

        if (hour != null && minute != null &&
            hour in 0..23 && minute in 0..59
        ) {
            val alarm = Intent(
                AlarmClock.ACTION_SET_ALARM
            ).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "THOR alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }

            launchIntent(
                alarm,
                "Opening the alarm screen for ${"%02d:%02d".format(hour, minute)}."
            )
        } else {
            addMessage("THOR", "That time is not valid.")
        }
    } else {
        addMessage(
            "THOR",
            "Use a 24-hour time, for example: alarm 07:30."
        )
    }
}


            lower.startsWith("search ") || lower.startsWith("google ") ||
                lower.startsWith("खोज ") || lower.startsWith("সার্চ ") -> {
                val query = command.substringAfter(' ').trim()
                if (query.isNotEmpty()) {
                    val url = "https://www.google.com/search?q=${Uri.encode(query)}"
                    launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opening web search.")
                }
            }

            lower.startsWith("open ") || lower.startsWith("launch ") ||
                lower.startsWith("खोलो ") || lower.startsWith("খোলো ") -> {
                val appName = command.substringAfter(' ').trim()
                openInstalledApp(appName)
            }

            else -> askGemini(command)
        }
    }

    /**
     * Optional wake listening works only while this screen is visible.
     * THOR does not keep the microphone active in the background.
     */
    private fun toggleWakeWord(button: Button) {
        if (wakeEnabled) {
            stopWakeWord()
            button.text = "WAKE: OFF"
            addMessage("THOR", "Wake listening stopped.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("THOR", "No speech-recognition service is available.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            return
        }
        startWakeWord(button)
    }

    private fun startWakeWord(button: Button) {
        wakeEnabled = true
        waitingForWakeCommand = false
        button.text = "WAKE: ON"
        addMessage("THOR", "Wake listening is active only while THOR stays open. Say Hey THOR, then your command.")
        if (wakeRecognizer == null) {
            wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onError(error: Int) { scheduleWakeListening(900L) }
                    override fun onResults(results: Bundle?) {
                        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (heard.isNotBlank()) {
                            val normalized = heard.lowercase(Locale.ROOT)
                            if (waitingForWakeCommand) {
                                waitingForWakeCommand = false
                                handleCommand(heard)
                            } else if (normalized.startsWith("hey thor ")) {
                                val directCommand = heard.substringAfter("hey thor", "").trim().trimStart(',', ':')
                                if (directCommand.isNotBlank()) {
                                    handleCommand(directCommand)
                                } else {
                                    waitingForWakeCommand = true
                                    addMessage("THOR", "I'm listening. Speak your command.")
                                    speak("I'm listening")
                                }
                            } else if (normalized.startsWith("thor ")) {
                                val directCommand = heard.substringAfter("thor", "").trim().trimStart(',', ':')
                                if (directCommand.isNotBlank()) handleCommand(directCommand)
                                else {
                                    waitingForWakeCommand = true
                                    addMessage("THOR", "I'm listening. Speak your command.")
                                    speak("I'm listening")
                                }
                            } else if (normalized.contains("hey thor") || normalized == "thor") {
                                waitingForWakeCommand = true
                                addMessage("THOR", "I'm listening. Speak your command.")
                                speak("I'm listening")
                            }
                        }
                        scheduleWakeListening(500L)
                    }
                })
            }
        }
        scheduleWakeListening(250L)
    }

    private fun scheduleWakeListening(delayMs: Long) {
        wakeHandler.removeCallbacksAndMessages(null)
        if (!wakeEnabled || isFinishing || isDestroyed) return
        wakeHandler.postDelayed({
            if (!wakeEnabled) return@postDelayed
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTags.getOrElse(language.selectedItemPosition) { "en-IN" })
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                wakeRecognizer?.startListening(intent)
            } catch (error: Exception) {
                addMessage("THOR", "Wake listening stopped: ${error.message ?: "speech service unavailable"}")
                stopWakeWord()
                wakeButton?.text = "WAKE: OFF"
            }
        }, delayMs)
    }

    private fun stopWakeWord() {
        wakeEnabled = false
        waitingForWakeCommand = false
        wakeHandler.removeCallbacksAndMessages(null)
        try { wakeRecognizer?.cancel() } catch (_: Exception) {}
        wakeRecognizer?.destroy()
        wakeRecognizer = null
    }

    @Deprecated("Legacy permission callback is used to avoid an extra AndroidX dependency.")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1001 -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
                else addMessage("THOR", "Microphone permission is required for voice input. Enable it in Android App info → Permissions.")
            }
            1002 -> {
                val button = wakeButton ?: return
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startWakeWord(button)
                } else {
                    addMessage("THOR", "Microphone permission is required for wake listening.")
                }
            }
            1004 -> {
                if (pendingBossVoiceEnrollment) {
                    pendingBossVoiceEnrollment = false
                    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                        startBossVoiceEnrollment()
                    } else {
                        bossSetupStatus?.text = "Microphone permission was not granted. You can allow it in Android settings and try again, or finish without a voice sample."
                    }
                }
            }
        }
    }

    private fun sendMediaKey(keyCode: Int, label: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        addMessage("THOR", label)
    }

    private fun initializeFirebase() {
        try {
            Firebase.initialize(context = this)
            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val providerFactory = if (isDebug) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            Firebase.appCheck.installAppCheckProviderFactory(providerFactory)
            firebaseReady = true
        } catch (error: Exception) {
            firebaseReady = false
            addMessage("THOR", "Firebase setup needs attention: ${error.message ?: "initialization failed"}")
        }
    }

    private fun showAiProviderSettings() {
        val manager = ThorAiProviderManager(this)
        val current = manager.settings()
        val providers = ThorAiProviderManager.Provider.values()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 8, 22, 4)
        }
        val providerSpinner = Spinner(this)
        providerSpinner.adapter = premiumSpinnerAdapter(providers.map { it.title })
        providerSpinner.setPopupBackgroundDrawable(glassDrawable(0xFF1D1E22.toInt(), 0x66D7B879, 16f))
        providerSpinner.setSelection(providers.indexOf(current.provider).coerceAtLeast(0))

        fun field(label: String, value: String, hint: String, password: Boolean = false): EditText {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(accentColor())
                setPadding(0, 10, 0, 3)
            })
            return EditText(this).apply {
                setSingleLine(true)
                setText(value)
                this.hint = hint
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { panel.addView(it, matchWrap()) }
        }

        fun modelOptions(provider: ThorAiProviderManager.Provider): List<String> = when (provider) {
            ThorAiProviderManager.Provider.GEMINI,
            ThorAiProviderManager.Provider.GEMINI_API -> listOf(
                "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash",
                "gemini-3.1-pro-preview", "gemini-3.5-flash-lite"
            )
            ThorAiProviderManager.Provider.OPENAI -> listOf(
                "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna",
                "gpt-5.6-sol", "gpt-5.5", "gpt-5.4", "gpt-4o-mini"
            )
            ThorAiProviderManager.Provider.ANTHROPIC -> listOf(
                "claude-fable-5-2", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"
            )
            ThorAiProviderManager.Provider.GROQ -> listOf(
                "llama-3.3-70b-versatile", "llama-4-scout-17b-16e-instruct",
                "qwen-qwq-32b", "deepseek-r1-distill-llama-70b"
            )
            ThorAiProviderManager.Provider.OPENROUTER -> listOf(
                "meta-llama/llama-3.3-70b-instruct:free",
                "deepseek/deepseek-chat", "google/gemini-2.5-flash",
                "anthropic/claude-sonnet-4.5", "openai/gpt-4o-mini"
            )
            ThorAiProviderManager.Provider.DEEPSEEK -> listOf("deepseek-chat", "deepseek-reasoner")
            ThorAiProviderManager.Provider.MISTRAL -> listOf(
                "mistral-large-latest", "mistral-small-latest", "codestral-latest"
            )
            ThorAiProviderManager.Provider.CLOUDFLARE -> listOf(
                "@cf/meta/llama-3.1-8b-instruct", "@cf/qwen/qwen3-30b-a3b-fp8"
            )
            ThorAiProviderManager.Provider.CUSTOM -> emptyList()
            ThorAiProviderManager.Provider.LOCAL -> emptyList()
        }

        panel.addView(TextView(this).apply {
            text = "AI provider"
            textSize = 13f
            setTextColor(accentColor())
        }, matchWrap())
        panel.addView(providerSpinner, matchWrap())

        panel.addView(TextView(this).apply {
            text = "Available models"
            textSize = 13f
            setTextColor(accentColor())
            setPadding(0, 10, 0, 3)
        }, matchWrap())
        val modelSpinner = Spinner(this)
        modelSpinner.setPopupBackgroundDrawable(glassDrawable(0xFF1D1E22.toInt(), 0x66D7B879, 16f))
        panel.addView(modelSpinner, matchWrap())
        val modelField = field("Selected model ID", current.model, "Choose above or enter a custom model ID")
        var activeModels = modelOptions(current.provider)
        fun updateModelChoices(provider: ThorAiProviderManager.Provider, selectedModel: String) {
            activeModels = modelOptions(provider)
            val choices = activeModels + "Custom model ID"
            modelSpinner.adapter = premiumSpinnerAdapter(choices)
            val selectedIndex = activeModels.indexOf(selectedModel).let { if (it >= 0) it else activeModels.size }
            modelSpinner.setSelection(selectedIndex.coerceIn(0, choices.lastIndex))
            modelField.setText(selectedModel)
        }
        updateModelChoices(current.provider, current.model)
        modelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position in activeModels.indices) modelField.setText(activeModels[position])
            }
        }

        val endpointField = field("API endpoint", current.endpoint, "HTTPS API endpoint")
        val keyField = field(
            "API key",
            "",
            if (current.apiKey.isNotBlank()) "Key saved securely on this device; blank keeps it" else "Paste your provider API key",
            true
        )
        val signInButton = Button(this).apply {
            text = "SIGN IN / GET API ACCESS"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = glassDrawable(0xFF292A30.toInt(), 0x88D7B879.toInt(), 18f)
        }
        panel.addView(signInButton, matchWrap())
        panel.addView(TextView(this).apply {
            text = "Choose a provider and a model. For providers that require credentials, this opens their official developer sign-in/key page. Return to THOR and paste the API key. A normal ChatGPT or Claude website subscription does not automatically provide API access. Your key is encrypted on this device."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 4)
        }, matchWrap())

        var previousProvider = current.provider
        fun applyProviderState(provider: ThorAiProviderManager.Provider) {
            val needsEndpointAndKey = provider != ThorAiProviderManager.Provider.GEMINI &&
                provider != ThorAiProviderManager.Provider.LOCAL
            modelField.isEnabled = provider != ThorAiProviderManager.Provider.LOCAL
            modelSpinner.isEnabled = provider != ThorAiProviderManager.Provider.LOCAL
            endpointField.isEnabled = needsEndpointAndKey
            keyField.isEnabled = needsEndpointAndKey
            signInButton.isEnabled = provider != ThorAiProviderManager.Provider.GEMINI &&
                provider != ThorAiProviderManager.Provider.LOCAL
            signInButton.alpha = if (signInButton.isEnabled) 1f else 0.45f
        }
        applyProviderState(current.provider)
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = providers[position]
                if (selected != previousProvider) {
                    val saved = manager.settings(selected)
                    updateModelChoices(selected, saved.model)
                    endpointField.setText(saved.endpoint)
                    keyField.setText("")
                    keyField.hint = if (saved.apiKey.isNotBlank()) {
                        "Key saved securely on this device; blank keeps it"
                    } else {
                        "Paste your provider API key"
                    }
                    previousProvider = selected
                }
                applyProviderState(selected)
            }
        }
        signInButton.setOnClickListener {
            val selected = providers[providerSpinner.selectedItemPosition]
            val url = when (selected) {
                ThorAiProviderManager.Provider.GEMINI_API -> "https://aistudio.google.com/app/apikey"
                ThorAiProviderManager.Provider.OPENAI -> "https://platform.openai.com/api-keys"
                ThorAiProviderManager.Provider.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
                ThorAiProviderManager.Provider.GROQ -> "https://console.groq.com/keys"
                ThorAiProviderManager.Provider.OPENROUTER -> "https://openrouter.ai/keys"
                ThorAiProviderManager.Provider.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
                ThorAiProviderManager.Provider.MISTRAL -> "https://console.mistral.ai/api-keys"
                ThorAiProviderManager.Provider.CLOUDFLARE -> "https://dash.cloudflare.com/"
                ThorAiProviderManager.Provider.CUSTOM -> "https://platform.openai.com/docs"
                else -> ""
            }
            if (url.isNotBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    Toast.makeText(this, "Sign in on the provider page, then return to THOR and paste your API key.", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not open provider sign-in page: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this)
            .setTitle("AI MODEL SELECTION")
            .setView(scroll)
            .setPositiveButton("SAVE") { _, _ ->
                val selected = providers[providerSpinner.selectedItemPosition]
                try {
                    manager.save(
                        selected,
                        modelField.text.toString(),
                        endpointField.text.toString(),
                        keyField.text.toString()
                    )
                    Toast.makeText(this, "THOR will now use ${selected.title} — ${modelField.text}.", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not save provider settings: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("REMOVE SAVED KEY") { _, _ ->
                val selected = providers[providerSpinner.selectedItemPosition]
                manager.clearKey(selected)
                Toast.makeText(this, "Saved API key removed from this device.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("CANCEL", null)
            .show().also { stylePremiumDialog(it) }
    }

    private fun askGemini(prompt: String) {
        val providerManager = ThorAiProviderManager(this)
        val selectedSettings = providerManager.settings()
        val selectedProvider = selectedSettings.provider
        if (selectedProvider == ThorAiProviderManager.Provider.LOCAL) {
            addMessage("THOR", "On-device AI is selected. No online request will be made.")
            askLocalOrFallback(prompt)
            return
        }

        val languageInstruction = when (language.selectedItemPosition) {
            1 -> "Reply in natural Hindi."
            2 -> "Reply in natural Bengali."
            else -> "Reply in clear English."
        }
        val styleInstruction = "You are THOR, a concise and helpful Android assistant. Answer the user's question directly, match their language and tone, and be accurate.\n$languageInstruction\n${learningMemory.systemContext()}\nUser: $prompt"

        if (selectedProvider != ThorAiProviderManager.Provider.GEMINI) {
            addMessage("THOR", "Connecting to ${selectedProvider.title}...")
            mainScope.launch {
                try {
                    val answer = providerManager.ask(styleInstruction)
                    addMessage("THOR", answer)
                    speak(answer)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e("THOR_AI_PROVIDER", "Selected provider failed", error)
                    addMessage("THOR", "${selectedProvider.title} request failed: ${error.message ?: error.javaClass.simpleName}. Switching to on-device AI.")
                    askLocalOrFallback(prompt)
                }
            }
            return
        }

        if (!firebaseReady) {
            addMessage("THOR", "Firebase Gemini is not ready. Check Firebase setup and App Check. Switching to on-device AI.")
            askLocalOrFallback(prompt)
            return
        }
        addMessage("THOR", "Thinking with ${selectedSettings.model}...")
        mainScope.launch {
            try {
                val answer = GeminiAssistant.ask(styleInstruction, selectedSettings.model)
                addMessage("THOR", answer)
                speak(answer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("THOR_GEMINI", "Gemini request failed", error)
                addMessage("THOR", "Gemini unavailable: ${error.message ?: error.javaClass.simpleName}. Switching to on-device AI.")
                askLocalOrFallback(prompt)
            }
        }
    }

    private fun offlineReply(prompt: String): String {
        val query = prompt.lowercase(Locale.ROOT)
        val casualEnglish = listOf(
            "bro", "bruh", "dude", "lol", "lmao", "rofl", "fr", "ngl",
            "idk", "ikr", "btw", "omg", "yo", "sup", "gonna", "wanna"
        )
        val casualBengali = listOf(
            "ভাই", "দোস্ত", "বন্ধু", "কিরে", "ধুর", "জোস", "দারুণ",
            "ফাটাফাটি", "কি অবস্থা", "কী অবস্থা", "বস", "আরে",
            "bhai", "dost", "bondhu", "ki re", "dhur", "darun", "joss"
        )
        val isCasual = casualEnglish.any { query.contains(it) } ||
            casualBengali.any { query.contains(it) }
        val bengaliInput = query.any { it in '\u0980'..'\u09FF' } ||
            listOf("bhai", "dost", "bondhu", "ki re", "dhur", "darun", "joss")
                .any { query.contains(it) }

        val reply = when {
            query.contains("hello") || query == "hi" || query.contains("নমস্কার") ||
                query.contains("হ্যালো") || query.contains("নমস্তে") ->
                if (bengaliInput) "ওই! THOR রেডি আছে। বলো, কী দরকার?"
                else "Yo! THOR's ready. What's up?"
            query.contains("time") || query.contains("সময়") || query.contains("সময়") ->
                if (bengaliInput) "এখন সময় " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()) + "।"
                else "It's " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()) + " right now."
            query.contains("date") || query.contains("তারিখ") ->
                if (bengaliInput) "আজ " + SimpleDateFormat("dd MMMM yyyy", Locale("bn", "IN")).format(Date()) + "।"
                else "Today's date is " + SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date()) + "."
            query.contains("your name") || query.contains("who are you") ||
                query.contains("তোর নাম") || query.contains("তোমার নাম") ->
                if (bengaliInput) "আমি THOR, তোর ফোনের assistant. কীভাবে help করব?"
                else "I'm THOR, your assistant. What do you need?"
            isCasual && bengaliInput ->
                "বুঝেছি ভাই! আমি এখন offline আছি, তাই সব প্রশ্নের AI-level উত্তর দিতে পারব না। তবে notes, tasks, apps আর phone-এর supported controls চালাতে পারি।"
            isCasual ->
                "Gotcha, bro. I'm offline right now, so I can't give a full AI answer. But I can still handle notes, tasks, apps, and supported phone controls."
            else ->
                "I am offline. I can still manage notes and tasks, open apps and settings, use the dialer, prepare SMS, set up Clock reminders, and control supported media playback. For open-ended AI answers, reconnect to the internet and check Firebase."
        }
        return reply
    }

    private fun startYoutubeFifthDownloadNavigation() {
        val serviceName = android.content.ComponentName(this, ThorNavigationService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val serviceEnabled = enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }

        if (!serviceEnabled) {
            val message = "Screen Navigation needs your permission first. Enable THOR Navigation in Android Accessibility settings, then repeat the command."
            addMessage("THOR", message)
            speak(message)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                addMessage("THOR", "Open Settings > Accessibility > THOR Navigation manually.")
            }
            return
        }

        val launch = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (launch == null) {
            val message = "YouTube is not installed or cannot be opened on this phone."
            addMessage("THOR", message)
            speak(message)
            return
        }

        getSharedPreferences("thor_navigation", MODE_PRIVATE).edit()
            .putString("task", "YOUTUBE_FIFTH_DOWNLOAD")
            .putString("stage", "WAIT_DOWNLOADS")
            .putString("seen_items", "")
            .apply()
        addMessage("THOR", "Opening YouTube. I'll look for Downloads and select the fifth listed video. You can stop the task by turning off THOR Navigation in Accessibility settings.")
        speak("Opening YouTube. Navigating to Downloads.")
        try {
            startActivity(launch)
        } catch (error: Exception) {
            getSharedPreferences("thor_navigation", MODE_PRIVATE).edit().clear().apply()
            addMessage("THOR", "I couldn't open YouTube: ${error.message ?: "launch failed"}")
        }
    }

    private fun openAppAndControlMedia(requestedName: String, action: String) {
        val wanted = requestedName.lowercase(Locale.ROOT)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull {
            it.loadLabel(packageManager).toString().trim().equals(wanted, ignoreCase = true)
        } ?: matches.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(wanted)
        }
        if (match == null) {
            val message = "I couldn't find $requestedName on this phone."
            addMessage("THOR", message)
            speak(message)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) {
            val message = "${match.loadLabel(packageManager)} does not provide a normal launch screen."
            addMessage("THOR", message)
            speak(message)
            return
        }
        try {
            startActivity(launch)
            val verb = if (action == "resume") "resuming" else "starting"
            val message = "Opening ${match.loadLabel(packageManager)} and $verb playback."
            addMessage("THOR", message)
            speak(message)
            // Give the player time to open and publish its media session.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    val key = if (action == "resume") KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PLAY
                    sendMediaKey(key, "Sent a play command to the active media player.")
                }
            }, 1400L)
        } catch (error: Exception) {
            val message = "I couldn't open $requestedName: ${error.message ?: "launch failed"}"
            addMessage("THOR", message)
            speak(message)
        }
    }

    private fun openInstalledApp(requestedName: String) {
        val wanted = requestedName.lowercase(Locale.ROOT)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(wanted)
        }
        if (match == null) {
            addMessage("THOR", "I couldn't find an app matching \"$requestedName\".")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch != null) launchIntent(launch, "Opening ${match.loadLabel(packageManager)}.")
        else addMessage("THOR", "This app does not provide a normal launch screen.")
    }

    

private fun launchIntent(
    intent: Intent,
    successMessage: String
) {
    try {
        startActivity(intent)
        addMessage("THOR", successMessage)

    } catch (e: Exception) {
        val error = "${e.javaClass.simpleName}: ${e.message}"
        addMessage("THOR", "DIAGNOSTIC: $error")
        android.util.Log.e("THOR_DEBUG", error, e)
    }
}




    private fun scheduleReminder(command: String) {
        val relative = Regex("""(?i)in\s+(\d+)\s+(minute|minutes|min|hour|hours|hr)""").find(command)
        val timeMatch = Regex("""(\d{1,2})[:.](\d{2})""").find(command)
        val message = when {
            relative != null -> command.substring(relative.range.last + 1).trim().trimStart('-', ':').ifBlank { "THOR reminder" }
            timeMatch != null -> command.substring(timeMatch.range.last + 1).trim().trimStart('-', ':').ifBlank { "THOR reminder" }
            else -> ""
        }
        val triggerAt = when {
            relative != null -> {
                val amount = relative.groupValues[1].toLongOrNull() ?: 0L
                val unit = relative.groupValues[2].lowercase(Locale.ROOT)
                if (amount <= 0L || amount > 10080L) {
                    addMessage("THOR", "Choose a reminder between 1 minute and 7 days from now.")
                    return
                }
                System.currentTimeMillis() + amount * if (unit.startsWith("hour") || unit == "hr") 3_600_000L else 60_000L
            }
            timeMatch != null -> {
                val hour = timeMatch.groupValues[1].toIntOrNull()
                val minute = timeMatch.groupValues[2].toIntOrNull()
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    addMessage("THOR", "That time is invalid. Use 24-hour time, like 07:30.")
                    return
                }
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                calendar.timeInMillis
            }
            else -> {
                addMessage("THOR", "Use: remind 07:30 Take my book, or remind in 10 minutes Take a break.")
                return
            }
        }
        val reminderText = message.ifBlank { "THOR reminder" }
        ThorReminderReceiver.schedule(this, triggerAt, reminderText)
        val whenText = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(triggerAt))
        addMessage("THOR", "Reminder scheduled for $whenText: $reminderText. Android may delay it in battery-saving modes.")
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
    }

    private fun helpText(): String =
        "Try these commands:\n" +
        "• open YouTube\n" +
        "• open Settings\n" +
        "• call 9876543210 (opens dialer; you confirm)\n" +
        "• sms 9876543210 (opens SMS app)\n" +
        "• remind 07:30 Take my book / remind in 10 minutes Stretch\n" +
        "• hey THOR (visible wake listening while THOR is open)\n" +
        "• pause music / next song / previous song / volume up\n" +
        "• wifi settings / bluetooth settings\n" +
        "• what time is it\n" +
        "• search space news\n" +
        "• teach me that no cap means I am serious\n" +
        "• what have you learned / forget learning X / clear learned memory\n" +
        "• install offline AI for open-ended offline chat\n\n" +
        "The offline language model is downloaded separately on demand (~586 MB), not bundled into each APK. Reminder notifications require notification permission; Android battery settings may delay them. Hindi/Bengali speech recognition depends on the installed speech service."

    private fun respond(english: String, hindi: String, bengali: String) {
        val answer = when (language.selectedItemPosition) {
            1 -> hindi
            2 -> bengali
            else -> english
        }
        addMessage("THOR", answer)
        speak(answer)
    }

    private fun addMessage(who: String, message: String) {
        chat.append("$who  ›  $message\n\n")
    }

    private fun speak(message: String) {
        if (ttsReady) {
            setTtsLanguage()
            applyVoiceStyle()
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "thor-${System.currentTimeMillis()}")
        }
    }

    private fun applyVoiceStyle() {
        val engine = tts ?: return
        when (voiceStyle.selectedItemPosition) {
            0 -> { engine.setPitch(0.82f); engine.setSpeechRate(0.90f) }
            1 -> { engine.setPitch(1.0f); engine.setSpeechRate(1.0f) }
            else -> { engine.setPitch(1.06f); engine.setSpeechRate(1.08f) }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            setTtsLanguage()
            applyVoiceStyle()
        }
    }

    private fun setTtsLanguage() {
        val locale = when (language.selectedItemPosition) {
            1 -> Locale("hi", "IN")
            2 -> Locale("bn", "IN")
            else -> Locale("en", "IN")
        }
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            tts?.language = locale
        } else {
            tts?.language = Locale.ENGLISH
            Toast.makeText(this, "That voice is not installed. Using English.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        stopTapRecognition()
        stopWakeWord()
        wakeButton?.text = "WAKE: OFF"
        super.onPause()
    }

    override fun onDestroy() {
        stopTapRecognition()
        stopWakeWord()
        try { bossRecorder?.stop() } catch (_: Exception) {}
        bossRecorder?.release()
        bossRecorder = null
        tts?.stop()
        tts?.shutdown()
        offlineAi.close()
        mainScope.cancel()
        super.onDestroy()
    }
}
