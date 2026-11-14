package com.thor.assistant

import androidx.fragment.app.FragmentActivity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.content.pm.ApplicationInfo
import com.google.firebase.Firebase
import com.google.firebase.initialize
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
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

class MainActivity : FragmentActivity(), TextToSpeech.OnInitListener {
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var language: Spinner
    private lateinit var voiceStyle: Spinner
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var liveSession: ThorLiveSession? = null
    private var liveOrb: ThorLiveOrbView? = null
    private var liveStatusText: TextView? = null
    private var liveCaptionText: TextView? = null
    private var liveToggleButton: Button? = null
    private var voiceStatus: TextView? = null
    private lateinit var localStore: ThorLocalStore
    private lateinit var learningMemory: ThorLearningMemory
    private lateinit var securityAudit: ThorSecurityAuditLog
    private lateinit var bossProfile: ThorBossProfile
    private var awaitingIdentityName = false
    private var masterVerifiedThisSession = false
    private val masterAuthStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user == null) {
            masterVerifiedThisSession = false
        } else {
            user.getIdToken(false)
                .addOnSuccessListener { tokenResult ->
                    val isMaster = tokenResult.claims["thorMaster"] == true
                    val sessionId = tokenResult.claims["thorSessionId"] as? String
                    if (isMaster && !sessionId.isNullOrBlank()) {
                        masterApprovalClient.saveSessionId(sessionId)
                        mainScope.launch {
                            val active = masterApprovalClient.checkSession()
                            masterVerifiedThisSession = active == true
                            when (active) {
                                true -> addMessage("THOR", "Master Mode restored. No app timer is set; say 'I quit' to end this device session.")
                                false -> {
                                    masterApprovalClient.clearSessionId()
                                    FirebaseAuth.getInstance().signOut()
                                    addMessage("THOR", "This Master session was revoked. Request approval again.")
                                }
                                null -> addMessage("THOR", "Network unavailable. Your saved session is retained, but Master Mode stays locked until its status can be checked.")
                            }
                        }
                    } else {
                        masterVerifiedThisSession = false
                    }
                }
                .addOnFailureListener {
                    masterVerifiedThisSession = false
                    addMessage("THOR", "Master Mode could not be revalidated. Connect to the internet and try again.")
                }
        }
    }
    private var bossSetupStatus: TextView? = null
    private var bossRecorder: MediaRecorder? = null
    private var bossSetupDialog: AlertDialog? = null
    private var pendingBossVoiceEnrollment = false
    private lateinit var offlineAi: ThorOfflineAi
    private var offlineAiButton: Button? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repeatingUiAnimators = mutableListOf<android.animation.Animator>()
    private var firebaseReady = false
    private lateinit var masterApprovalClient: ThorMasterApprovalClient
    private var wakeRecognizer: SpeechRecognizer? = null
    private var tapRecognizer: SpeechRecognizer? = null
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var wakeEnabled = false
    private var waitingForWakeCommand = false
    private var wakeButton: Button? = null
    private val wakeButtons = mutableListOf<Button>()
    private var backgroundWakeButton: Button? = null
    private var pendingBackgroundWakeEnable = false

    private val languages = listOf("English", "Hindi", "Bengali")
    private val languageTags = listOf("en-IN", "hi-IN", "bn-IN")
    private val voiceStyles = listOf("Cinematic Deep", "Natural", "Crisp Assistant", "JARVIS • British AI")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localStore = ThorLocalStore(this)
        learningMemory = ThorLearningMemory(this)
        securityAudit = ThorSecurityAuditLog(this)
        bossProfile = ThorBossProfile(this)
        offlineAi = ThorOfflineAi(applicationContext)
        masterApprovalClient = ThorMasterApprovalClient(this)
        initializeFirebase()
        tts = TextToSpeech(this, this)
        buildScreen()
        intent.getStringExtra("thor_background_command")
            ?.takeIf { it.isNotBlank() }
            ?.let { command ->
                intent.removeExtra("thor_background_command")
                mainScope.launch {
                    delay(500L)
                    handleCommand(command)
                }
            }
        addMessage("THOR", "Systems ready. Type a command or tap the microphone.")
        showBossSetupIfNeeded()
        processPendingApprovalIntent()
        // Keep app startup fast and memory use predictable. Load the large local model only
        // when the user selects Offline AI or explicitly asks for an offline response.
        if (offlineAi.isModelInstalled() || offlineAi.hasBundledModel()) {
            offlineAiButton?.text = "LOAD OFFLINE AI"
        }
    }

    override fun onStart() {
        super.onStart()
        ThorReminderReceiver.restorePending(this)
        if (firebaseReady) {
            FirebaseAuth.getInstance().addAuthStateListener(masterAuthStateListener)
        }
        if (isBackgroundWakeEnabled()) startBackgroundWakeService()
    }

    override fun onStop() {
        if (firebaseReady) {
            FirebaseAuth.getInstance().removeAuthStateListener(masterAuthStateListener)
        }
        super.onStop()
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
            text = "Set up this installation's local Boss profile. Remote Master Mode uses Device A fingerprint approval after the secure Worker is deployed. Voice matching remains experimental."
            textSize = 14f
        }
        val status = TextView(this).apply {
            text = "Enter your name, then record your voice."
            textSize = 13f
        }
        bossSetupStatus = status
        val recordButton = Button(this).apply { text = "ENROLL BOSS VOICE (3 × 5 SECONDS)" }
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
            processPendingApprovalIntent()
            addMessage("THOR", "Welcome, " + bossProfile.bossName() + ". Your Boss profile is saved on this device. Voice matching is not enabled yet.")
            speak("Welcome, Boss.")
        }
        dialog.show()
    }

    private fun startBossVoiceEnrollment() {
        if (bossProfile.bossName().isBlank()) {
            bossSetupStatus?.text = "Enter the Boss name first."
            return
        }
        bossSetupStatus?.text = "Preparing the on-device speaker model..."
        mainScope.launch {
            var engine: ThorSpeakerEngine? = null
            try {
                val samples = mutableListOf<FloatArray>()
                val speakerEngine = withContext(Dispatchers.IO) { ThorSpeakerEngine(applicationContext) }
                engine = speakerEngine
                repeat(3) { index ->
                    bossSetupStatus?.text = "Sample ${index + 1} of 3: speak naturally for five seconds."
                    speak("Voice sample ${index + 1}. Please speak naturally.")
                    delay(1_500L)
                    val embedding = withContext(Dispatchers.IO) { speakerEngine.captureEmbedding() }
                    samples += embedding
                }
                val template = ThorSpeakerMatch().createBossTemplate(samples)
                bossProfile.saveBossEmbedding(ThorSpeakerMatch().encode(template))
                bossProfile.finishSetup()
                bossSetupDialog?.dismiss()
                processPendingApprovalIntent()
                addMessage(
                    "THOR",
                    "Three voice samples were processed on this device. Your local speaker template is saved for future verification work. Automatic Boss recognition is not enabled until the match threshold is calibrated on this phone."
                )
                speak("Voice profile saved. Welcome, Boss.")
            } catch (error: Exception) {
                bossSetupStatus?.text = "Voice enrollment failed: ${error.message ?: "model or microphone unavailable"}. You can retry or finish without a voice sample."
                android.util.Log.e("THOR_BOSS", "Speaker embedding enrollment failed", error)
            } finally {
                withContext(Dispatchers.IO) {
                    try { engine?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildScreen() {
        val background = Color.rgb(5, 9, 18)
        val panel = Color.rgb(10, 19, 34)
        val blue = Color.rgb(45, 190, 255)
        val muted = Color.rgb(145, 170, 195)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 18, 16, 8)
            setBackgroundColor(background)
        }

        val contentFrame = android.widget.FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun page(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(background)
        }

        fun scrollPage(body: LinearLayout): ScrollView = ScrollView(this).apply {
            addView(body)
            isFillViewport = true
        }

        val homeBody = page()
        val homeScroll = scrollPage(homeBody)
        val homeTitle = TextView(this).apply {
            text = "THOR"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(blue)
            letterSpacing = 0.22f
        }
        homeBody.addView(homeTitle, matchWrap())
        val homeTitleAnimator = ObjectAnimator.ofFloat(homeTitle, "alpha", 0.72f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        repeatingUiAnimators += homeTitleAnimator
        homeTitleAnimator.start()
        homeBody.addView(TextView(this).apply {
            text = "YOUR PERSONAL AI ASSISTANT"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            letterSpacing = 0.12f
        }, matchWrap())
        homeBody.addView(ThorHudView(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (220 * resources.displayMetrics.density).toInt()
        ).apply { setMargins(0, 18, 0, 18) })
        homeBody.addView(TextView(this).apply {
            text = "READY WHEN YOU ARE"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(blue)
            letterSpacing = 0.1f
        }, matchWrap())
        val homeMic = makeButton("TAP TO SPEAK")
        homeMic.setTextColor(Color.WHITE)
        homeMic.setBackgroundColor(Color.rgb(0, 105, 180))
        homeMic.setOnClickListener { startVoiceInput() }
        homeBody.addView(homeMic, matchWrap())
        val homeWake = makeButton("WAKE WORD: OFF")
        homeWake.setOnClickListener { toggleWakeWord(homeWake) }
        wakeButton = homeWake
        wakeButtons += homeWake
        homeBody.addView(homeWake, matchWrap())
        contentFrame.addView(homeScroll, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val livePage = buildLivePage()
        contentFrame.addView(livePage, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val chatBody = page()
        val chatScroll = ScrollView(this)
        chat = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(225, 237, 248))
            setPadding(12, 16, 12, 16)
        }
        chatScroll.addView(chat)
        chatBody.addView(chatScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        input = EditText(this).apply {
            hint = "Message THOR..."
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(14, 12, 14, 12)
            setBackgroundColor(panel)
        }
        chatBody.addView(input, matchWrap())
        val chatActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val send = makeButton("SEND")
        send.setOnClickListener {
            val command = input.text.toString().trim()
            if (command.isNotEmpty()) {
                input.setText("")
                handleCommand(command)
            }
        }
        val mic = makeButton("MIC")
        mic.setOnClickListener { startVoiceInput() }
        val help = makeButton("HELP")
        help.setOnClickListener { addMessage("THOR", helpText()) }
        chatActions.addView(send, weightedButton())
        chatActions.addView(mic, weightedButton())
        chatActions.addView(help, weightedButton())
        chatBody.addView(chatActions, matchWrap())
        contentFrame.addView(chatBody, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val featuresBody = page()
        featuresBody.addView(TextView(this).apply {
            text = "FEATURES"
            textSize = 22f
            setTextColor(blue)
            setPadding(4, 8, 4, 16)
        }, matchWrap())
        language = Spinner(this)
        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        val savedLanguage = getSharedPreferences("thor_voice", MODE_PRIVATE).getString("language", "en-IN")
        language.setSelection(languageTags.indexOf(savedLanguage).coerceAtLeast(0))
        language.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                getSharedPreferences("thor_voice", MODE_PRIVATE).edit()
                    .putString("language", languageTags.getOrElse(position) { "en-IN" }).apply()
                setTtsLanguage()
                applyVoiceStyle()
            }
        }
        featuresBody.addView(sectionLabel("Assistant language"), matchWrap())
        featuresBody.addView(language, matchWrap())
        val offlineButton = makeButton("INSTALL OFFLINE AI (~586 MB)")
        offlineAiButton = offlineButton
        offlineButton.setOnClickListener { installOfflineAi() }
        featuresBody.addView(offlineButton, matchWrap())
        val masterSetupButton = makeButton("MASTER MODE SETUP")
        masterSetupButton.setOnClickListener { showMasterModeSetupDialog() }
        featuresBody.addView(masterSetupButton, matchWrap())
        val memoryButton = makeButton("PERSONAL MEMORY")
        memoryButton.setOnClickListener { showPersonalMemoryManager() }
        featuresBody.addView(memoryButton, matchWrap())
        contentFrame.addView(scrollPage(featuresBody), android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { })
        val featuresPage = contentFrame.getChildAt(contentFrame.childCount - 1)

        val securityBody = page()
        securityBody.addView(TextView(this).apply {
            text = "PRIVACY & SECURITY"
            textSize = 22f
            setTextColor(blue)
            setPadding(4, 8, 4, 16)
        }, matchWrap())
        val securityButton = makeButton("OPEN SECURITY CENTRE")
        securityButton.setOnClickListener { showSecurityCentre() }
        securityBody.addView(securityButton, matchWrap())
        val activityButton = makeButton("SECURITY ACTIVITY")
        activityButton.setOnClickListener { showSecurityActivity() }
        securityBody.addView(activityButton, matchWrap())
        contentFrame.addView(scrollPage(securityBody), android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val securityPage = contentFrame.getChildAt(contentFrame.childCount - 1)

        val settingsBody = page()
        settingsBody.addView(TextView(this).apply {
            text = "SETTINGS"
            textSize = 22f
            setTextColor(blue)
            setPadding(4, 8, 4, 16)
        }, matchWrap())
        val aiProviderButton = makeButton("AI PROVIDER: " + ThorAiProviderManager(this).settings().provider.title)
        aiProviderButton.setOnClickListener { showAiProviderSettings(aiProviderButton) }
        settingsBody.addView(aiProviderButton, matchWrap())
        settingsBody.addView(TextView(this).apply {
            text = "Choose Gemini, OpenAI / ChatGPT, Groq, OpenRouter, Cloudflare, a compatible custom API, or on-device AI. API keys are encrypted on this phone."
            textSize = 12f
            setTextColor(muted)
        }, matchWrap())
        voiceStyle = Spinner(this)
        voiceStyle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceStyles)
        val voicePrefs = getSharedPreferences("thor_voice", MODE_PRIVATE)
        // One-time voice migration: make the requested cinematic narrator the default
        // for existing installs without repeatedly overriding the user's later choice.
        if (!voicePrefs.getBoolean("cinematic_narrator_v1", false)) {
            voicePrefs.edit()
                .putInt("style", 0)
                .putBoolean("cinematic_narrator_v1", true)
                .apply()
        }
        voiceStyle.setSelection(voicePrefs.getInt("style", 0).coerceIn(0, voiceStyles.lastIndex))
        voiceStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                getSharedPreferences("thor_voice", MODE_PRIVATE).edit().putInt("style", position).apply()
                setTtsLanguage()
                applyVoiceStyle()
            }
        }
        settingsBody.addView(sectionLabel("Voice style"), matchWrap())
        settingsBody.addView(voiceStyle, matchWrap())
        val hybridVoiceStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(4, 4, 4, 8)
            text = "Hybrid voice: checking installed English, Hindi and Bengali voices..."
        }
        voiceStatus = hybridVoiceStatus
        settingsBody.addView(hybridVoiceStatus, matchWrap())
        val refreshVoice = makeButton("REFRESH HYBRID VOICE")
        refreshVoice.setOnClickListener {
            setTtsLanguage()
            applyVoiceStyle()
            val status = voiceStatus?.text ?: "Voice availability refreshed."
            Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        }
        settingsBody.addView(refreshVoice, matchWrap())
        val previewVoice = makeButton("PREVIEW VOICE")
        previewVoice.setOnClickListener {
            val sample = when (language.selectedItemPosition) {
                1 -> "नमस्ते, मैं थॉर हूँ। मैं आपकी सहायता के लिए तैयार हूँ।"
                2 -> "নমস্কার, আমি থর। আমি আপনাকে সাহায্য করতে প্রস্তুত।"
                else -> if (voiceStyle.selectedItemPosition == 3)
                    "Good day. THOR is online. How may I assist you?"
                else "Hello. I am THOR. Systems are ready."
            }
            speak(sample)
        }
        settingsBody.addView(previewVoice, matchWrap())
        val systemVoiceSettings = makeButton("VOICE ENGINE SETTINGS")
        systemVoiceSettings.setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        settingsBody.addView(systemVoiceSettings, matchWrap())
        val wakeSettings = makeButton("WAKE WORD: OFF")
        wakeSettings.setOnClickListener { toggleWakeWord(wakeSettings) }
        wakeButtons += wakeSettings
        settingsBody.addView(wakeSettings, matchWrap())

        val backgroundWakeSettings = makeButton(
            if (isBackgroundWakeEnabled()) "BACKGROUND WAKE: ON" else "BACKGROUND WAKE: OFF"
        )
        backgroundWakeButton = backgroundWakeSettings
        backgroundWakeSettings.setOnClickListener { toggleBackgroundWake(backgroundWakeSettings) }
        settingsBody.addView(backgroundWakeSettings, matchWrap())
        settingsBody.addView(TextView(this).apply {
            text = "Background listening uses a visible microphone notification. When THOR hears a command outside the app, tap its notification to let THOR execute it."
            textSize = 12f
            setTextColor(muted)
        }, matchWrap())
        contentFrame.addView(scrollPage(settingsBody), android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val settingsPage = contentFrame.getChildAt(contentFrame.childCount - 1)

        val pages = listOf(homeScroll, chatBody, livePage, featuresPage, securityPage, settingsPage)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 4)
            setBackgroundColor(Color.rgb(8, 16, 29))
        }
        val navLabels = listOf("HOME", "CHAT", "LIVE", "FEATURES", "SECURITY", "SETTINGS")
        val navButtons = mutableListOf<Button>()
        fun showTab(index: Int) {
            if (index != 2 && liveSession != null) stopThorLive()
            pages.forEachIndexed { pageIndex, view ->
                view.visibility = if (pageIndex == index) android.view.View.VISIBLE else android.view.View.GONE
            }
            navButtons.forEachIndexed { buttonIndex, button ->
                button.setTextColor(if (buttonIndex == index) blue else muted)
                button.setBackgroundColor(if (buttonIndex == index) Color.rgb(15, 43, 68) else Color.TRANSPARENT)
            }
        }
        navLabels.forEachIndexed { index, label ->
            val button = makeButton(label).apply {
                textSize = 9.5f
                setTextColor(muted)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showTab(index) }
            }
            navButtons.add(button)
            nav.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
        showTab(0)
        showStartupAnimation(contentFrame, blue, muted, background)
    }

    private fun buildLivePage(): LinearLayout {
        val cyan = Color.rgb(65, 198, 255)
        val muted = Color.rgb(145, 170, 195)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 22, 18, 18)
            setBackgroundColor(Color.rgb(5, 9, 18))
        }
        body.addView(TextView(this).apply {
            text = "THOR LIVE"
            textSize = 27f
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            setTextColor(cyan)
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "LIVE AUDIO • TEMPORARY SESSION • NO CHAT HISTORY"
            textSize = 9f
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setTextColor(muted)
        }, matchWrap())

        val orb = ThorLiveOrbView(this)
        liveOrb = orb
        body.addView(orb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (300 * resources.displayMetrics.density).toInt()
        ).apply { setMargins(0, 14, 0, 10) })

        val status = TextView(this).apply {
            text = "READY FOR A LIVE CONVERSATION"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(cyan)
        }
        liveStatusText = status
        body.addView(status, matchWrap())

        val caption = TextView(this).apply {
            text = "Your conversation stays temporary and is cleared when the session ends."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(205, 220, 235))
            maxLines = 2
            setPadding(12, 10, 12, 14)
        }
        liveCaptionText = caption
        body.addView(caption, matchWrap())

        val toggle = makeButton("START LIVE SESSION").apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 105, 180))
            setOnClickListener {
                if (liveSession == null) startThorLive() else stopThorLive()
            }
        }
        liveToggleButton = toggle
        body.addView(toggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(8, 12, 8, 8) })

        body.addView(TextView(this).apply {
            text = "Use headphones for clearer full-duplex conversation. Live Mode needs an internet connection."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(10, 8, 10, 0)
        }, matchWrap())
        return body
    }

    private fun liveServiceUrl(): String =
        getSharedPreferences("thor_live_service", MODE_PRIVATE).getString("worker_url", "").orEmpty().trim()

    private fun showLiveServiceSetupDialog() {
        val endpoint = EditText(this).apply {
            hint = "https://your-thor-worker.workers.dev"
            setSingleLine(true)
            setText(liveServiceUrl())
        }
        AlertDialog.Builder(this)
            .setTitle("THOR Live Service Setup")
            .setMessage("Enter the HTTPS Cloudflare Worker URL used for temporary Gemini Live access. This setting is separate from Master Mode.")
            .setView(endpoint)
            .setPositiveButton("SAVE & START") { _, _ ->
                val value = endpoint.text.toString().trim().trimEnd('/')
                val uri = runCatching { java.net.URI(value) }.getOrNull()
                val valid = value.length < 300 &&
                    uri != null &&
                    uri.scheme.equals("https", ignoreCase = true) &&
                    uri.host?.endsWith(".workers.dev", ignoreCase = true) == true &&
                    uri.userInfo == null &&
                    uri.port in -1..443 &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.path.orEmpty().trimEnd('/').isEmpty()
                if (!valid) {
                    addMessage("THOR", "Enter a valid HTTPS Cloudflare workers.dev URL without a path.")
                    return@setPositiveButton
                }
                getSharedPreferences("thor_live_service", MODE_PRIVATE)
                    .edit().putString("worker_url", value).apply()
                startThorLive()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startThorLive() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1006)
            return
        }
        if (liveServiceUrl().isBlank()) {
            showLiveServiceSetupDialog()
            return
        }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            liveStatusText?.text = "SIGNING IN SECURELY"
            auth.signInAnonymously()
                .addOnSuccessListener { beginThorLiveSession() }
                .addOnFailureListener { error ->
                    val message = "Live Mode needs Firebase Authentication. Enable Anonymous sign-in in Firebase Console, or sign in through Master Mode. " +
                        (error.localizedMessage ?: "")
                    liveStatusText?.text = "SIGN-IN REQUIRED"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
        } else {
            beginThorLiveSession()
        }
    }

    private fun beginThorLiveSession() {
        if (liveSession != null || isFinishing || isDestroyed) return
        if (isBackgroundWakeEnabled()) stopBackgroundWakeService()
        val voiceName = when (voiceStyle.selectedItemPosition) {
            1 -> "Aoede"
            2 -> "Kore"
            3 -> "Charon"
            else -> "Charon"
        }
        val languageName = when (language.selectedItemPosition) {
            1 -> "Hindi"
            2 -> "Bengali"
            else -> "English"
        }
        lateinit var session: ThorLiveSession
        session = ThorLiveSession(this, object : ThorLiveSession.Listener {
            override fun onState(state: ThorLiveSession.State, detail: String) {
                liveOrb?.setLiveState(state)
                liveStatusText?.text = detail
                liveToggleButton?.text = if (state == ThorLiveSession.State.IDLE ||
                    state == ThorLiveSession.State.ERROR) "START LIVE SESSION" else "END LIVE SESSION"
                if (state == ThorLiveSession.State.ERROR && liveSession === session) {
                    liveSession = null
                    restartBackgroundWakeServiceIfEnabled()
                }
                if (state == ThorLiveSession.State.IDLE || state == ThorLiveSession.State.ERROR) {
                    liveCaptionText?.text = "Your live conversation has ended. No transcript was saved."
                }
            }

            override fun onTranscript(speaker: String, text: String) {
                // Only the latest caption is rendered; no transcript list or database writes.
                liveCaptionText?.text = "$speaker  •  $text"
            }

            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
        liveSession = session
        liveToggleButton?.text = "CONNECTING..."
        liveStatusText?.text = "SECURING LIVE CONNECTION"
        mainScope.launch {
            try {
                session.start(liveServiceUrl(), voiceName, languageName)
            } catch (error: Exception) {
                if (liveSession === session) liveSession = null
                liveOrb?.setLiveState(ThorLiveSession.State.IDLE)
                liveStatusText?.text = "LIVE MODE UNAVAILABLE"
                liveToggleButton?.text = "START LIVE SESSION"
                liveCaptionText?.text = error.message ?: "Could not start the live session."
                restartBackgroundWakeServiceIfEnabled()
            }
        }
    }

    private fun stopThorLive(resumeBackgroundWake: Boolean = true) {
        val session = liveSession ?: return
        liveSession = null
        session.stop()
        liveOrb?.setLiveState(ThorLiveSession.State.IDLE)
        liveStatusText?.text = "SESSION ENDED"
        liveToggleButton?.text = "START LIVE SESSION"
        liveCaptionText?.text = "Your live conversation has ended. No transcript was saved."
        if (resumeBackgroundWake) restartBackgroundWakeServiceIfEnabled()
    }

    private fun showStartupAnimation(
        host: android.widget.FrameLayout,
        blue: Int,
        muted: Int,
        background: Int
    ) {
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(background)
            alpha = 0f
            isClickable = true
            isFocusable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        val reactor = ThorHudView(this)
        panel.addView(reactor, LinearLayout.LayoutParams(
            (220 * resources.displayMetrics.density).toInt(),
            (220 * resources.displayMetrics.density).toInt()
        ).apply { gravity = Gravity.CENTER })
        val title = TextView(this).apply {
            text = "THOR"
            textSize = 34f
            gravity = Gravity.CENTER
            letterSpacing = 0.28f
            setTextColor(blue)
            setPadding(0, 18, 0, 4)
        }
        panel.addView(title, matchWrap())
        val subtitle = TextView(this).apply {
            text = "INITIALIZING SYSTEMS"
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            setTextColor(muted)
        }
        panel.addView(subtitle, matchWrap())
        val status = TextView(this).apply {
            text = "POWERING UP  •  PLEASE WAIT"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(blue)
            setPadding(0, 16, 0, 0)
        }
        panel.addView(status, matchWrap())
        overlay.addView(panel, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        host.addView(overlay, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val reactorAnimator = ObjectAnimator.ofFloat(reactor, "rotation", 0f, 360f).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        val titleAnimator = ObjectAnimator.ofFloat(title, "alpha", 0.55f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        repeatingUiAnimators += reactorAnimator
        repeatingUiAnimators += titleAnimator
        reactorAnimator.start()
        titleAnimator.start()
        overlay.animate().alpha(1f).setDuration(450L).withEndAction {
            Handler(Looper.getMainLooper()).postDelayed({
                overlay.animate().alpha(0f).setDuration(550L).withEndAction {
                    reactorAnimator.cancel()
                    titleAnimator.cancel()
                    repeatingUiAnimators.remove(reactorAnimator)
                    repeatingUiAnimators.remove(titleAnimator)
                    host.removeView(overlay)
                }.start()
            }, 1900L)
        }.start()
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(145, 190, 220))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 10 }

    private fun weightedButton() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { setMargins(4, 4, 4, 4) }

    private fun makeButton(label: String) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
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
                "\n\nInstall the offline AI model using the button near the top of THOR to enable open-ended offline chat."
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

    private fun handleIdentityName(identity: String) {
        val normalized = identity.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (normalized == "deep dikshit") {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                showMasterModeSetupDialog()
            } else {
                user.getIdToken(false)
                    .addOnSuccessListener { tokenResult ->
                        val sessionId = tokenResult.claims["thorSessionId"] as? String
                        if (tokenResult.claims["thorMaster"] == true && !sessionId.isNullOrBlank()) {
                            masterApprovalClient.saveSessionId(sessionId)
                            mainScope.launch {
                                val active = masterApprovalClient.checkSession()
                                masterVerifiedThisSession = active == true
                                val message = when (active) {
                                    true -> "Welcome back, Master. Your session is active."
                                    false -> "That Master session is no longer valid. Request approval again."
                                    null -> "I can't reach the approval service. Reconnect before using Master Mode."
                                }
                                addMessage("THOR", message)
                                speak(message)
                                if (active == false) FirebaseAuth.getInstance().signOut()
                            }
                        } else {
                            showMasterModeSetupDialog()
                        }
                    }
                    .addOnFailureListener {
                        addMessage("THOR", "I couldn't verify the saved Master session. Please connect to the internet and try again.")
                    }
            }
        } else {
            val message = "Hello, $identity. I haven't verified you as Master."
            addMessage("THOR", message)
            speak(message)
        }
    }

    private fun showMasterModeSetupDialog() {
        if (!firebaseReady) {
            addMessage("THOR", "Firebase is not initialized. Check the Firebase configuration first.")
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
        }
        val endpoint = EditText(this).apply {
            hint = "Cloudflare Worker URL (https://....workers.dev)"
            setSingleLine(true)
            setText(masterApprovalClient.baseUrl)
        }
        val email = EditText(this).apply {
            hint = "Firebase account email"
            setSingleLine(true)
            setText(ThorMasterModeConfig.MASTER_EMAIL)
        }
        val password = EditText(this).apply {
            hint = "Firebase account password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        panel.addView(endpoint)
        panel.addView(email)
        panel.addView(password)
        AlertDialog.Builder(this)
            .setTitle("Remote Master Mode")
            .setMessage("Sign in with a Firebase Email/Password account. Device A must use the account whose UID is configured as MASTER_UID in Cloudflare. Device B should use its own Firebase account.")
            .setView(panel)
            .setPositiveButton("SET UP DEVICE A") { _, _ ->
                masterApprovalClient.baseUrl = endpoint.text.toString()
                authenticateAndRegisterApprover(email.text.toString().trim(), password.text.toString())
            }
            .setNegativeButton("REQUEST ON DEVICE B") { _, _ ->
                masterApprovalClient.baseUrl = endpoint.text.toString()
                authenticateAndRequestMaster(email.text.toString().trim(), password.text.toString())
            }
            .setNeutralButton("CANCEL", null)
            .show()
    }

    private fun authenticateAndRegisterApprover(email: String, password: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            addMessage("THOR", "Allow notifications, then tap MASTER MODE SETUP again so Device A can receive approval alerts.")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1005)
            return
        }
        if (email.isBlank() || password.isBlank()) {
            addMessage("THOR", "Enter the trusted Device A Firebase email and password.")
            return
        }
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                try {
                    val approval = ThorDeviceApproval(this)
                    val publicKey = if (approval.hasKey()) approval.publicKeyBase64() else approval.createKey()
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            mainScope.launch {
                                try {
                                    masterApprovalClient.registerApprover(publicKey, token)
                                    addMessage("THOR", "Device A is registered as the trusted approval phone. Keep notifications enabled.")
                                    speak("Trusted phone registered.")
                                } catch (error: Exception) {
                                    addMessage("THOR", "Device A registration failed: ${error.message}")
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            addMessage("THOR", "Could not obtain the notification token: ${error.message}")
                        }
                } catch (error: Exception) {
                    addMessage("THOR", "Secure key setup failed: ${error.message}")
                }
            }
            .addOnFailureListener { error ->
                addMessage("THOR", "Firebase sign-in failed: ${error.localizedMessage}")
            }
    }

    private fun authenticateAndRequestMaster(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            addMessage("THOR", "Enter this Device B Firebase email and password.")
            return
        }
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                mainScope.launch {
                    try {
                        masterApprovalClient.registerRequester()
                        val request = masterApprovalClient.requestApproval()
                        val requestId = request.getString("requestId")
                        addMessage("THOR", "Approval request sent to Device A. Waiting for fingerprint approval...")
                        val deadline = System.currentTimeMillis() + 180_000L
                        while (System.currentTimeMillis() < deadline) {
                            val result = masterApprovalClient.requestResult(requestId)
                            when (result.optString("status")) {
                                "approved" -> {
                                    val customToken = result.optString("customToken")
                                    val sessionId = result.optString("sessionId")
                                    if (customToken.isBlank() || sessionId.isBlank()) error("The approval server returned an incomplete session.")
                                    masterApprovalClient.saveSessionId(sessionId)
                                    FirebaseAuth.getInstance().signInWithCustomToken(customToken)
                                        .addOnSuccessListener {
                                            masterVerifiedThisSession = true
                                            addMessage("THOR", "Fingerprint approval verified. Master Mode is active on this device.")
                                            speak("Master Mode unlocked.")
                                        }
                                        .addOnFailureListener { authError ->
                                            masterApprovalClient.clearSessionId()
                                            addMessage("THOR", "Master session sign-in failed: ${authError.localizedMessage}")
                                        }
                                    return@launch
                                }
                                "expired", "denied", "revoked" -> error("The approval request is ${result.optString("status")}.")
                            }
                            delay(3000L)
                        }
                        addMessage("THOR", "No approval arrived before the three-minute request expired.")
                    } catch (error: Exception) {
                        addMessage("THOR", "Master approval failed: ${error.message}")
                    }
                }
            }
            .addOnFailureListener { error ->
                addMessage("THOR", "Firebase sign-in failed: ${error.localizedMessage}")
            }
    }

    private fun showActiveMasterSessions() {
        mainScope.launch {
            try {
                val sessions = masterApprovalClient.activeSessions()
                if (sessions.length() == 0) {
                    addMessage("THOR", "There are no active Master Mode sessions to revoke.")
                    return@launch
                }
                val ids = (0 until sessions.length()).map { index ->
                    sessions.getJSONObject(index).optString("session_id")
                }
                val labels = (0 until sessions.length()).map { index ->
                    val item = sessions.getJSONObject(index)
                    "Device ${item.optString("requester_device_id").take(8)} • ${item.optString("created_at")}"
                }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Active Master Mode sessions")
                    .setItems(labels) { _, which ->
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Revoke this Device B Master Mode session?")
                            .setPositiveButton("REVOKE") { _, _ ->
                                mainScope.launch {
                                    try {
                                        masterApprovalClient.revokeSession(ids[which])
                                        addMessage("THOR", "Selected Master Mode session revoked. Device B will be locked the next time it checks in.")
                                    } catch (error: Exception) {
                                        addMessage("THOR", "Session revocation failed: ${error.message}")
                                    }
                                }
                            }
                            .setNegativeButton("CANCEL", null)
                            .show()
                    }
                    .setNegativeButton("CLOSE", null)
                    .show()
            } catch (error: Exception) {
                addMessage("THOR", "Could not load active sessions: ${error.message}")
            }
        }
    }

    private fun reviewMasterApproval(requestId: String) {
        mainScope.launch {
            try {
                val details = masterApprovalClient.requestDetails(requestId)
                val challenge = details.getString("challenge")
                val requester = details.optString("requester_uid", "Unknown device")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Approve Master Mode?")
                    .setMessage("A signed-in device is requesting Master Mode.\n\nRequester: $requester\n\nApprove only if you initiated this request.")
                    .setPositiveButton("VERIFY FINGERPRINT") { _, _ ->
                        val approval = ThorDeviceApproval(this@MainActivity)
                        approval.authenticateAndSign(challenge.toByteArray(Charsets.UTF_8)) { signed ->
                            signed.onSuccess { bytes ->
                                val signature = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                mainScope.launch {
                                    try {
                                        masterApprovalClient.approve(requestId, signature)
                                        addMessage("THOR", "Fingerprint verified. Master Mode approval sent to the requesting device.")
                                        speak("Approval completed.")
                                    } catch (error: Exception) {
                                        addMessage("THOR", "Approval rejected: ${error.message}")
                                    }
                                }
                            }.onFailure { error ->
                                addMessage("THOR", "Fingerprint approval cancelled or failed: ${error.message}")
                            }
                        }
                    }
                    .setNegativeButton("DENY") { _, _ ->
                        mainScope.launch {
                            try {
                                masterApprovalClient.deny(requestId)
                                addMessage("THOR", "Approval request denied. No Master session was created.")
                            } catch (error: Exception) {
                                addMessage("THOR", "Could not record denial: ${error.message}")
                            }
                        }
                    }
                    .show()
            } catch (error: Exception) {
                addMessage("THOR", "Could not load this approval request: ${error.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processPendingApprovalIntent()
        intent.getStringExtra("thor_background_command")
            ?.takeIf { it.isNotBlank() }
            ?.let { command ->
                intent.removeExtra("thor_background_command")
                mainScope.launch {
                    delay(350L)
                    handleCommand(command)
                }
            }
    }

    private fun processPendingApprovalIntent() {
        if (!bossProfile.isConfigured()) return
        val requestId = intent.getStringExtra("thor_approval_request_id") ?: return
        if (requestId.isBlank()) return
        intent.removeExtra("thor_approval_request_id")
        window.decorView.post { reviewMasterApproval(requestId) }
    }

    private fun startVoiceInput() {
        // Avoid multiple recognizers competing for the same microphone.
        stopWakeWord()
        wakeButton?.text = "WAKE: OFF"
        if (isBackgroundWakeEnabled()) stopBackgroundWakeService()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            restartBackgroundWakeServiceIfEnabled()
            addMessage("THOR", "No compatible speech-recognition service is installed or enabled on this device. Install or enable a speech service such as Google Speech Services, then try again.")
            return
        }
        try {
            tapRecognizer?.cancel()
            tapRecognizer?.destroy()
            tapRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).also { recognizer ->
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
                        android.util.Log.e("THOR_MIC", "Tap recognition error=$error")
                        addMessage("THOR", speechErrorMessage(error))
                        tapRecognizer?.destroy()
                        tapRecognizer = null
                        restartBackgroundWakeServiceIfEnabled()
                    }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()
                        tapRecognizer?.destroy()
                        tapRecognizer = null
                        restartBackgroundWakeServiceIfEnabled()
                        if (!spoken.isNullOrBlank()) handleCommand(spoken)
                        else addMessage("THOR", "I didn't catch that. Please try again.")
                    }
                })
            }
            val tag = languageTags.getOrElse(language.selectedItemPosition) { "en-IN" }
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Do not force offline recognition: many devices have no downloaded offline language pack.
            }
            tapRecognizer?.startListening(request)
        } catch (error: Exception) {
            android.util.Log.e("THOR_MIC", "Could not start tap recognition", error)
            tapRecognizer?.destroy()
            tapRecognizer = null
            restartBackgroundWakeServiceIfEnabled()
            addMessage("THOR", "Microphone could not start: ${error.message ?: error.javaClass.simpleName}. Check that another app is not using the microphone.")
        }
    }

    private fun speechErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The speech service could not access the microphone audio. Check Android's microphone privacy switch and close other apps using the mic."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android denied microphone access to THOR. Allow Microphone in App info → Permissions."
        SpeechRecognizer.ERROR_CLIENT -> "The speech service stopped unexpectedly. Close and reopen THOR, then retry."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The speech service is unavailable. Check internet access or install its required language pack."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This speech service does not support the selected language. Try another language or install its speech pack."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Another app or recognition session is using the speech service. Wait a moment and retry."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I couldn't hear clear speech. Check microphone access and try speaking again."
        else -> "Speech recognition failed (error $error). Check the device's speech service and microphone privacy settings."
    }

    @Deprecated("Legacy activity result API is used to avoid an extra AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val spoken = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) handleCommand(spoken)
        }
    }

    private fun handleCommand(raw: String) {
        addMessage("YOU", raw)
        val command = raw.trim()
        val lower = command.lowercase(Locale.ROOT)

        if (awaitingIdentityName) {
            awaitingIdentityName = false
            handleIdentityName(command)
            return
        }

        when {
            lower == "i quit" || lower == "thor i quit" -> {
                masterVerifiedThisSession = false
                securityAudit.record("Master Mode sign-out requested")
                val endingSession = masterApprovalClient.sessionId()
                masterApprovalClient.clearSessionId()
                if (endingSession.isNotBlank()) {
                    mainScope.launch {
                        try { masterApprovalClient.endSession(endingSession) } catch (_: Exception) {}
                        FirebaseAuth.getInstance().signOut()
                    }
                } else {
                    FirebaseAuth.getInstance().signOut()
                }
                awaitingIdentityName = false
                val localBoss = bossProfile.bossName().ifBlank { "the local Boss" }
                val message = "Master Mode sign-out requested on this device. If the approval service is offline, Device A can revoke the server session when it reconnects. General Boss mode restored, $localBoss."
                addMessage("THOR", message)
                speak(message)
            }
            lower in listOf("revoke master sessions", "manage master sessions") -> {
                // The Worker independently enforces MASTER_UID and registered-approver
                // device authorization. Do not trust this client-side flag as a security boundary.
                showActiveMasterSessions()
            }
            lower in listOf("personal memory", "open memory", "memory manager", "show personal memory") -> showPersonalMemoryManager()
            lower in listOf("security centre", "security center", "open security", "security dashboard") -> showSecurityCentre()
            lower in listOf("security activity", "show security log") -> showSecurityActivity()
            lower in listOf("master mode status", "master status") -> {
                val status = if (masterVerifiedThisSession) "Master Mode is active on this device." else "Master Mode is not active on this device."
                addMessage("THOR", status)
                speak(status)
            }
            lower in listOf("who am i", "identify me", "what is my identity", "কে আমি", "मैं कौन हूँ") -> {
                awaitingIdentityName = true
                val prompt = "Please tell me your name."
                addMessage("THOR", prompt)
                speak(prompt)
            }
            lower.startsWith("my name is ") || lower.startsWith("i am ") || lower.startsWith("this is ") -> {
                val identity = when {
                    lower.startsWith("my name is ") -> command.substring(11).trim()
                    lower.startsWith("i am ") -> command.substring(5).trim()
                    else -> command.substring(8).trim()
                }
                handleIdentityName(identity)
            }
            learningMemory.parseTeaching(command)?.let { learned ->
                addMessage("THOR", learned)
                speak(learned)
            } != null -> Unit

            lower in listOf("what have you learned", "show learned memory", "show what you learned", "তুমি কী শিখেছ", "क्या सीखा") -> {
                val entries = learningMemory.all()
                addMessage("THOR", if (entries.isEmpty()) "I haven't saved any lessons yet." else
                    entries.mapIndexed { index, entry -> "${index + 1}. ${entry.first} — ${entry.second}" }.joinToString("\n"))
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

            lower.contains("youtube") && lower.contains("download") && lower.contains("play") -> {
                val itemNumber = extractOrdinal(lower)
                if (itemNumber == null) {
                    val message = "Which downloaded video should I play? For example, say: play the sixth video in YouTube Downloads."
                    addMessage("THOR", message)
                    speak(message)
                } else {
                    startNavigationTask(
                        "com.google.android.youtube",
                        "YOUTUBE_DOWNLOAD_INDEX",
                        "WAIT_DOWNLOADS",
                        itemNumber
                    )
                }
            }

            lower.contains("youtube") && lower.contains("download") ->
                startNavigationTask("com.google.android.youtube", "YOUTUBE_DOWNLOADS", "WAIT_DOWNLOADS", 0)

            lower.contains("instagram") && (lower.contains("reel") || lower.contains("short video")) ->
                startNavigationTask("com.instagram.android", "INSTAGRAM_REELS", "WAIT_REELS", 0)

            lower in listOf("install offline ai", "load offline ai", "enable offline ai") ->
                installOfflineAi()

            lower in listOf("help", "what can you do", "commands", "मदद", "সাহায্য") ->
                addMessage("THOR", helpText())

            lower.trim().trimEnd('!', '.', '?', ',') in listOf(
                "hi", "hello", "hey", "hey thor", "yo", "sup",
                "হাই", "হ্যালো", "নমস্কার", "नमस्ते", "हेलो"
            ) -> respond(
                "Good day. THOR is online. How may I assist you?",
                "नमस्ते। THOR तैयार है। मैं आपकी क्या सहायता कर सकता हूँ?",
                "নমস্কার। THOR প্রস্তুত। কীভাবে সাহায্য করতে পারি?"
            )

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

    private fun isBackgroundWakeEnabled(): Boolean =
        getSharedPreferences("thor_wake", MODE_PRIVATE).getBoolean("background_enabled", false)

    private fun startBackgroundWakeService() {
        if (!isBackgroundWakeEnabled()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            backgroundWakeButton?.text = "BACKGROUND WAKE: OFF"
            addMessage("THOR", "Background Wake was switched off because microphone permission is no longer available.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            backgroundWakeButton?.text = "BACKGROUND WAKE: OFF"
            addMessage("THOR", "Background Wake needs notification permission so its microphone use remains visible. Enable notifications in Android App info.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            backgroundWakeButton?.text = "BACKGROUND WAKE: OFF"
            addMessage("THOR", "Background Wake needs an installed Android speech-recognition service. The setting has been switched off.")
            return
        }
        try {
            val service = Intent(this, ThorBackgroundWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            backgroundWakeButton?.text = "BACKGROUND WAKE: ON"
        } catch (error: Exception) {
            getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            backgroundWakeButton?.text = "BACKGROUND WAKE: OFF"
            addMessage("THOR", "Android could not start Background Wake: ${error.message ?: "service unavailable"}.")
        }
    }

    private fun stopBackgroundWakeService() {
        stopService(Intent(this, ThorBackgroundWakeService::class.java))
    }

    private fun restartBackgroundWakeServiceIfEnabled() {
        if (isBackgroundWakeEnabled() && liveSession == null) startBackgroundWakeService()
    }

    private fun toggleBackgroundWake(button: Button) {
        if (isBackgroundWakeEnabled()) {
            getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            stopBackgroundWakeService()
            button.text = "BACKGROUND WAKE: OFF"
            addMessage("THOR", "Background Wake is disabled.")
            speak("Background Wake is disabled.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("THOR", "Background Wake needs an installed Android speech-recognition service. Install or enable one, then try again.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundWakeEnable = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1007)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundWakeEnable = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1008)
            return
        }
        setBackgroundWakeEnabled(true, button)
    }

    private fun setBackgroundWakeEnabled(enabled: Boolean, button: Button?) {
        getSharedPreferences("thor_wake", MODE_PRIVATE).edit().putBoolean("background_enabled", enabled).apply()
        if (enabled) {
            stopWakeWord()
            wakeButtons.forEach { it.text = "WAKE: OFF" }
            startBackgroundWakeService()
            if (isBackgroundWakeEnabled()) {
                addMessage("THOR", "Background Wake is active. Keep the ongoing microphone notification visible; Android battery controls may affect availability.")
                speak("Background Wake is active.")
            }
        } else {
            stopBackgroundWakeService()
            button?.text = "BACKGROUND WAKE: OFF"
        }
    }

    private fun toggleWakeWord(button: Button) {
        if (wakeEnabled) {
            stopWakeWord()
            button.text = "WAKE: OFF"
            addMessage("THOR", "Wake listening stopped.")
            return
        }
        if (isBackgroundWakeEnabled()) {
            addMessage("THOR", "Turn off Background Wake in Settings before enabling in-app Wake Word, so only one microphone listener is active.")
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
        try { tapRecognizer?.cancel() } catch (_: Exception) {}
        tapRecognizer?.destroy()
        tapRecognizer = null
        wakeEnabled = true
        waitingForWakeCommand = false
        wakeButtons.forEach { it.text = "WAKE: ON" }
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
                    override fun onError(error: Int) {
                        android.util.Log.e("THOR_WAKE", "Speech recognition error=$error: ${speechErrorMessage(error)}")
                        val retryDelay = when (error) {
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 450L
                            SpeechRecognizer.ERROR_NO_MATCH -> 550L
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1_600L
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2_000L
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                addMessage("THOR", speechErrorMessage(error))
                                stopWakeWord()
                                wakeButton?.text = "WAKE: OFF"
                                return
                            }
                            else -> 1_100L
                        }
                        scheduleWakeListening(retryDelay)
                    }
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
                    // Let the installed recognition provider choose network or local processing.
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
        wakeButtons.forEach { it.text = "WAKE: OFF" }
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
                else {
                    addMessage("THOR", "Microphone permission is required for voice input. Enable it in Android App info → Permissions.")
                    restartBackgroundWakeServiceIfEnabled()
                }
            }
            1002 -> {
                val button = wakeButton ?: return
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startWakeWord(button)
                } else {
                    addMessage("THOR", "Microphone permission was denied. Wake listening remains off.")
                }
            }
            1007 -> {
                val button = backgroundWakeButton
                pendingBackgroundWakeEnable = false
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1008)
                    } else {
                        pendingBackgroundWakeEnable = false
                        setBackgroundWakeEnabled(true, button)
                    }
                } else {
                    pendingBackgroundWakeEnable = false
                    getSharedPreferences("thor_wake", MODE_PRIVATE).edit()
                        .putBoolean("background_enabled", false).apply()
                    button?.text = "BACKGROUND WAKE: OFF"
                    addMessage("THOR", "Microphone permission was denied. Background Wake remains off.")
                }
            }
            1008 -> {
                val button = backgroundWakeButton
                if (pendingBackgroundWakeEnable &&
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                ) {
                    pendingBackgroundWakeEnable = false
                    setBackgroundWakeEnabled(true, button)
                } else {
                    pendingBackgroundWakeEnable = false
                    getSharedPreferences("thor_wake", MODE_PRIVATE).edit()
                        .putBoolean("background_enabled", false).apply()
                    button?.text = "BACKGROUND WAKE: OFF"
                    addMessage("THOR", "Notification permission is required to keep Background Wake visibly active. Enable notifications in Android App info, then try again.")
                }
            }
            1003 -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    ThorReminderReceiver.restorePending(this)
                    addMessage("THOR", "Notifications are enabled. Any due THOR reminders have been restored.")
                } else {
                    addMessage("THOR", "Reminder alarms are saved, but Android notification permission is off. Enable it in app settings to receive reminder alerts.")
                }
            }
            1006 -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startThorLive()
                else Toast.makeText(this, "Microphone permission is required for THOR Live.", Toast.LENGTH_LONG).show()
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

    private fun showAiProviderSettings(providerButton: Button) {
        val manager = ThorAiProviderManager(this)
        val current = manager.settings()
        val providers = ThorAiProviderManager.Provider.values()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
        }
        val providerSpinner = Spinner(this)
        providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.title }
        )
        providerSpinner.setSelection(providers.indexOf(current.provider).coerceAtLeast(0))
        fun field(label: String, value: String, hint: String, password: Boolean = false): EditText {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.rgb(45, 190, 255))
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
        panel.addView(TextView(this).apply { text = "AI provider"; textSize = 13f })
        panel.addView(providerSpinner, matchWrap())
        val modelField = field("Model name", current.model, "Enter provider model ID")
        val endpointField = field("API endpoint", current.endpoint, "HTTPS chat-completions URL")
        val keyField = field(
            "API key",
            "",
            if (current.apiKey.isNotBlank()) "Key saved encrypted; blank keeps it" else "Paste your own key",
            true
        )
        val help = TextView(this).apply {
            text = "Firebase Gemini uses THOR's managed connection. Gemini API uses your own Google AI Studio key. OpenAI uses your own API key; API billing is separate from ChatGPT subscriptions. Groq and OpenRouter use standard endpoints. Cloudflare/custom need a full HTTPS chat-completions endpoint. Provider quotas and charges still apply."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 4)
        }
        panel.addView(help, matchWrap())
        var previousProvider = current.provider
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = providers[position]
                if (selected != previousProvider) {
                    val saved = manager.settings(selected)
                    modelField.setText(saved.model)
                    endpointField.setText(saved.endpoint)
                    keyField.hint = if (saved.apiKey.isNotBlank()) "API key saved securely; blank keeps it" else "Paste your API key"
                    previousProvider = selected
                }
                val needsKey = selected != ThorAiProviderManager.Provider.GEMINI &&
                    selected != ThorAiProviderManager.Provider.LOCAL
                keyField.isEnabled = needsKey
                endpointField.isEnabled = selected != ThorAiProviderManager.Provider.GEMINI &&
                    selected != ThorAiProviderManager.Provider.LOCAL
                modelField.isEnabled = selected != ThorAiProviderManager.Provider.GEMINI &&
                    selected != ThorAiProviderManager.Provider.LOCAL
            }
        }
        AlertDialog.Builder(this)
            .setTitle("THOR AI PROVIDER")
            .setView(panel)
            .setPositiveButton("SAVE") { _, _ ->
                val selected = providers[providerSpinner.selectedItemPosition]
                manager.save(
                    selected,
                    modelField.text.toString(),
                    endpointField.text.toString(),
                    keyField.text.toString()
                )
                providerButton.text = "AI PROVIDER: " + selected.title
                Toast.makeText(this, "AI provider saved for this device.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("REMOVE SAVED KEY") { _, _ ->
                val selected = providers[providerSpinner.selectedItemPosition]
                manager.clearKey(selected)
                Toast.makeText(this, "Saved API key removed from this device.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun askGemini(prompt: String) {
        val providerManager = ThorAiProviderManager(this)
        val selectedProvider = providerManager.settings().provider
        if (selectedProvider == ThorAiProviderManager.Provider.LOCAL) {
            addMessage("THOR", "Local AI selected. No online request will be made.")
            askLocalOrFallback(prompt)
            return
        }
        if (selectedProvider != ThorAiProviderManager.Provider.GEMINI) {
            val languageInstruction = when (language.selectedItemPosition) {
                1 -> "Reply in natural Hindi."
                2 -> "Reply in natural Bengali."
                else -> "Reply in clear English."
            }
            val compactPrompt = prompt.trim().take(2000)
            val styleInstruction = "You are THOR, a helpful personal assistant. Be concise and useful. Match the user's language and tone."
            val memoryContext = learningMemory.systemContext().take(1200)
            addMessage("THOR", "Connecting to ${selectedProvider.title}...")
            mainScope.launch {
                try {
                    val answer = providerManager.ask("$styleInstruction\n$languageInstruction\n$memoryContext\nUser: $compactPrompt")
                    addMessage("THOR", answer)
                    speak(answer)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val detail = error.message ?: error.javaClass.simpleName
                    android.util.Log.e("THOR_AI_PROVIDER", "Selected provider failed", error)
                    addMessage("THOR", "${selectedProvider.title} unavailable: ${detail.take(220)} Switching to local AI.")
                    askLocalOrFallback(prompt)
                }
            }
            return
        }
        if (!firebaseReady) {
            askLocalOrFallback(prompt)
            return
        }

        val prefs = getSharedPreferences("thor_data_saver", MODE_PRIVATE)
        val quotaBlockedUntil = prefs.getLong("gemini_quota_blocked_until", 0L)
        if (System.currentTimeMillis() < quotaBlockedUntil) {
            val minutes = ((quotaBlockedUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
            addMessage("THOR", "Gemini quota cooldown is active for about $minutes more minutes. Using offline AI to save requests.")
            askLocalOrFallback(prompt)
            return
        }

        val compactPrompt = prompt.trim().take(2000)
        val languageInstruction = when (language.selectedItemPosition) {
            1 -> "Reply in natural Hindi."
            2 -> "Reply in natural Bengali."
            else -> "Reply in clear English."
        }
        val styleInstruction = "Match the user's language and casual tone. Be concise and helpful."
        android.util.Log.i("THOR_GEMINI", "Gemini request started (data-saver mode)")
        addMessage("THOR", "Connecting to online AI...")
        mainScope.launch {
            try {
                val memoryContext = learningMemory.systemContext().take(1200)
                val answer = GeminiAssistant.ask("$languageInstruction\n$styleInstruction\n$memoryContext\nUser: $compactPrompt")
                prefs.edit().remove("gemini_quota_blocked_until").apply()
                android.util.Log.i("THOR_GEMINI", "Gemini request succeeded")
                addMessage("THOR", answer)
                speak(answer)
            } catch (error: TimeoutCancellationException) {
                android.util.Log.w("THOR_GEMINI", "Gemini timed out; switching to on-device AI", error)
                addMessage("THOR", "Online AI took too long. Switching to the on-device AI.")
                askLocalOrFallback(prompt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("THOR_GEMINI", "Gemini request failed", error)
                val detail = error.message ?: error.javaClass.simpleName
                val quotaError = detail.contains("quota", ignoreCase = true) ||
                    detail.contains("resource exhausted", ignoreCase = true) ||
                    detail.contains("429", ignoreCase = true)
                if (quotaError) {
                    // One-hour cooldown avoids repeated requests after a free-tier quota error.
                    prefs.edit().putLong(
                        "gemini_quota_blocked_until",
                        System.currentTimeMillis() + 60L * 60L * 1000L
                    ).apply()
                    addMessage("THOR", "Gemini's free quota is exhausted. I'll use offline AI and won't retry online for one hour.")
                } else {
                    addMessage("THOR", "Online AI unavailable. Switching to offline AI.")
                }
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

    private fun extractOrdinal(command: String): Int? {
        val words = mapOf(
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4,
            "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
            "ninth" to 9, "tenth" to 10
        )
        words.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(command) }?.let { return it.value }
        val numeric = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b").find(command)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return numeric?.takeIf { it in 1..20 }
    }

    private fun startNavigationTask(
        appPackage: String,
        task: String,
        stage: String,
        itemNumber: Int
    ) {
        val serviceName = android.content.ComponentName(this, ThorNavigationService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val serviceEnabled = enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }

        if (!serviceEnabled) {
            val message = "Screen Navigation needs your permission. Enable THOR Navigation in Android Accessibility settings, then repeat your command."
            addMessage("THOR", message)
            speak(message)
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { addMessage("THOR", "Open Settings > Accessibility > THOR Navigation manually.") }
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(appPackage)
        if (launch == null) {
            val appName = if (appPackage == "com.instagram.android") "Instagram" else "YouTube"
            val message = "$appName is not installed or cannot be opened on this phone."
            addMessage("THOR", message)
            speak(message)
            return
        }

        getSharedPreferences("thor_navigation", MODE_PRIVATE).edit()
            .putString("task", task)
            .putString("stage", stage)
            .putInt("item_number", itemNumber)
            .putLong("created_at", System.currentTimeMillis())
            .putString("seen_items", "")
            .apply()

        val description = when (task) {
            "YOUTUBE_DOWNLOAD_INDEX" -> "Opening YouTube Downloads and looking for video number $itemNumber."
            "YOUTUBE_DOWNLOADS" -> "Opening YouTube and navigating to Downloads."
            "INSTAGRAM_REELS" -> "Opening Instagram and navigating to Reels."
            else -> "Opening the requested app."
        }
        addMessage("THOR", "$description Screen navigation is best-effort and depends on the app's current layout.")
        speak(description)
        try {
            startActivity(launch)
        } catch (error: Exception) {
            getSharedPreferences("thor_navigation", MODE_PRIVATE).edit().clear().apply()
            addMessage("THOR", "I couldn't open the app: ${error.message ?: "launch failed"}")
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
        val request = try {
            ReminderCommandParser.parse(command)
        } catch (error: IllegalArgumentException) {
            addMessage("THOR", error.message ?: "I couldn't understand that reminder.")
            return
        }
        val triggerAt = request.relativeDelayMillis?.let { System.currentTimeMillis() + it } ?: run {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, request.hour!!)
                set(Calendar.MINUTE, request.minute!!)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            calendar.timeInMillis
        }
        val reminderText = request.message.ifBlank { "THOR reminder" }
        ThorReminderReceiver.schedule(this, triggerAt, reminderText)
        val whenText = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(triggerAt))
        addMessage("THOR", "Reminder scheduled for $whenText: $reminderText. Android may delay it in battery-saving modes.")
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
    }

    private fun showPersonalMemoryManager() {
        val entries = learningMemory.all()
        securityAudit.record("Personal memory manager opened")
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Personal Memory")
                .setMessage("No personal lessons are saved yet. Teach THOR with: teach me that 'no cap' means 'I'm being serious'.\n\nSaved lessons stay in this app's private local storage.")
                .setPositiveButton("CLOSE", null)
                .show()
            return
        }
        val labels = entries.map { "${it.first} — ${it.second}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Personal Memory • ${entries.size} saved")
            .setItems(labels) { _, which ->
                val topic = entries[which].first
                AlertDialog.Builder(this)
                    .setTitle("Forget “$topic”?")
                    .setMessage("This removes the selected saved lesson from this device.")
                    .setPositiveButton("FORGET") { _, _ ->
                        if (learningMemory.forget(topic)) {
                            securityAudit.record("One personal memory item deleted")
                            addMessage("THOR", "I removed that saved memory.")
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            .setNeutralButton("CLEAR ALL") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Clear all personal memory?")
                    .setMessage("All user-taught lessons will be removed from this device. Notes and tasks are not affected.")
                    .setPositiveButton("CLEAR") { _, _ ->
                        learningMemory.clear()
                        securityAudit.record("All personal memory items cleared")
                        addMessage("THOR", "All personal memory lessons have been cleared.")
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showSecurityCentre() {
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val approvalKey = try { ThorDeviceApproval(this).hasKey() } catch (_: Exception) { false }
        val session = masterApprovalClient.sessionId()
        val sessionLabel = if (session.isBlank()) "No saved session" else "Session ID stored (••••${session.takeLast(4)})"
        val workerLabel = if (masterApprovalClient.baseUrl.isBlank()) "Not configured" else "HTTPS endpoint configured"
        val report = listOf(
            "Master Mode: ${if (masterVerifiedThisSession) "verified on this app session" else "not verified"}",
            "Remote approval service: $workerLabel",
            "Saved Master session: $sessionLabel",
            "Device A approval key: ${if (approvalKey) "present in Android Keystore" else "not present on this device"}",
            "Microphone permission: ${if (audio) "granted" else "not granted"}",
            "Notifications: ${if (notifications) "allowed or not required" else "not allowed"}",
            "Memory storage: app-private local preferences (not end-to-end encrypted)",
            "Security activity: stored locally on this device"
        ).joinToString("\n\n")
        securityAudit.record("Privacy and Security Centre opened")
        val options = arrayOf(
            "Review / revoke Master Mode sessions",
            "View security activity",
            "Open Android app permissions",
            "Close"
        )
        AlertDialog.Builder(this)
            .setTitle("THOR • Privacy & Security")
            .setMessage(report)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showActiveMasterSessions()
                    1 -> showSecurityActivity()
                    2 -> {
                        securityAudit.record("Android app permission settings opened")
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        launchIntent(intent, "Opening Android's THOR permission settings.")
                    }
                }
            }
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun showSecurityActivity() {
        val events = securityAudit.events()
        val message = if (events.isEmpty()) "No security events have been recorded yet."
            else events.joinToString("\n\n") { "${it.first} • ${it.second}" }
        AlertDialog.Builder(this)
            .setTitle("Security Activity")
            .setMessage(message)
            .setNeutralButton("CLEAR LOG") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Clear the local security activity history?")
                    .setPositiveButton("CLEAR") { _, _ ->
                        securityAudit.clear()
                        addMessage("THOR", "Local security activity history cleared.")
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun helpText(): String =
        "Try these commands:\n" +
        "• open YouTube\n" +
        "• open Settings\n" +
        "• call 9876543210 (opens dialer; you confirm)\n" +
        "• sms 9876543210 (opens SMS app)\n" +
        "• remind 07:30 Take my book / remind in 10 minutes Stretch\n" +
        "• hey THOR (visible wake listening while THOR is open)\n" +
        "• Enable BACKGROUND WAKE in Settings for listening while THOR is in the background; tap its notification to run a heard command\n" +
        "• pause music / next song / previous song / volume up\n" +
        "• wifi settings / bluetooth settings\n" +
        "• what time is it\n" +
        "• search space news\n" +
        "• teach me that no cap means I am serious\n" +
        "• what have you learned / forget learning X / clear learned memory\n" +
        "• install offline AI for open-ended offline chat\n\n" +
        "The offline language model is bundled into this APK build (~586 MB). Reminder notifications require notification permission; Android battery settings may delay them. Hindi/Bengali speech recognition depends on the installed speech service."

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

    private var pendingSpeech: String? = null

    private fun speak(message: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = message
            return
        }
        setTtsLanguage()
        applyVoiceStyle()
        val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "thor-${System.currentTimeMillis()}")
        if (result == TextToSpeech.ERROR) {
            android.util.Log.e("THOR_TTS", "TTS engine rejected speech")
            Toast.makeText(this, "THOR voice failed. Check the installed text-to-speech engine and media volume.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Hybrid voice policy:
     * - With a validated internet connection, prefer a language-matched network voice.
     * - Offline, prefer a language-matched voice that does not require the network.
     * - If the preferred class is unavailable, fall back to any installed voice for the language,
     *   then Android's language/default voice.
     */
    private fun hasValidatedInternet(): Boolean {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun applyVoiceStyle() {
        val engine = tts ?: return
        val wantedLocale = when (language.selectedItemPosition) {
            1 -> Locale("hi", "IN")
            2 -> Locale("bn", "IN")
            else -> if (voiceStyle.selectedItemPosition == 3) Locale.UK else Locale("en", "IN")
        }
        val available = engine.voices.orEmpty().filter {
            it.locale.language.equals(wantedLocale.language, ignoreCase = true)
        }
        // Prefer a higher-quality network voice when internet is validated; fall back
        // to an installed offline voice, then any installed voice for this language.
        val preferNetwork = hasValidatedInternet()
        val preferred = if (preferNetwork) {
            available.filter { it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
                ?: available.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
        } else {
            available.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
                ?: available.filter { it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
        }
        val selected = preferred ?: available.maxByOrNull { it.quality }
        if (selected != null) {
            try {
                engine.voice = selected
                val mode = if (selected.isNetworkConnectionRequired) "ONLINE" else "OFFLINE"
                val languageName = when (selected.locale.language) {
                    "hi" -> "Hindi"
                    "bn" -> "Bengali"
                    else -> "English"
                }
                voiceStatus?.text = "Hybrid voice: $mode • $languageName • ${selected.name}" +
                    if (preferred == null) " (using installed fallback voice)" else ""
            } catch (error: Exception) {
                voiceStatus?.text = "Hybrid voice: Android default voice • ${error.message ?: "voice selection unavailable"}"
            }
        } else {
            voiceStatus?.text = "No installed voice is available for this language. Install voice data in Android Text-to-Speech settings."
        }

        when (voiceStyle.selectedItemPosition) {
            // Lower pitch and slightly slower delivery create a deeper, cinematic
            // narrator effect while retaining intelligibility across Hindi and English.
            0 -> { engine.setPitch(0.76f); engine.setSpeechRate(0.88f) }
            1 -> { engine.setPitch(1.0f); engine.setSpeechRate(1.0f) }
            2 -> { engine.setPitch(1.06f); engine.setSpeechRate(1.08f) }
            3 -> {
                engine.setPitch(0.90f)
                engine.setSpeechRate(0.92f)
            }
        }
    }

    private fun setTtsLanguage() {
        val locale = when (language.selectedItemPosition) {
            1 -> Locale("hi", "IN")
            2 -> Locale("bn", "IN")
            else -> if (voiceStyle.selectedItemPosition == 3) Locale.UK else Locale("en", "IN")
        }
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            tts?.language = locale
        } else {
            val languageOnly = Locale(locale.language)
            val languageResult = tts?.isLanguageAvailable(languageOnly) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (languageResult >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = languageOnly
                voiceStatus?.text = "Hybrid voice: using the installed ${locale.displayLanguage} voice without a regional variant."
            } else {
                tts?.language = Locale.ENGLISH
                voiceStatus?.text = "Hybrid voice: ${locale.displayLanguage} voice data is unavailable. Install it in Android TTS settings."
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setTtsLanguage()
            applyVoiceStyle()
            pendingSpeech?.let { queued ->
                pendingSpeech = null
                speak(queued)
            }
        } else {
            android.util.Log.e("THOR_TTS", "TextToSpeech initialization failed: $status")
            runOnUiThread {
                Toast.makeText(this, "THOR voice engine could not start. Check Android Text-to-speech settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        if (liveSession != null) stopThorLive(resumeBackgroundWake = false)
        try { tapRecognizer?.cancel() } catch (_: Exception) {}
        tapRecognizer?.destroy()
        tapRecognizer = null
        stopWakeWord()
        wakeButton?.text = "WAKE: OFF"
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        stopThorLive(resumeBackgroundWake = false)
        stopWakeWord()
        try { bossRecorder?.stop() } catch (_: Exception) {}
        bossRecorder?.release()
        bossRecorder = null
        tts?.stop()
        tts?.shutdown()
        mainScope.cancel()
        repeatingUiAnimators.forEach { it.cancel() }
        repeatingUiAnimators.clear()
        offlineAi.close()
        super.onDestroy()
    }
}
