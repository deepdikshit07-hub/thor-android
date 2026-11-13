package com.thor.assistant

import androidx.fragment.app.FragmentActivity
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

class MainActivity : FragmentActivity(), TextToSpeech.OnInitListener {
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var language: Spinner
    private lateinit var voiceStyle: Spinner
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
    private var firebaseReady = false
    private lateinit var masterApprovalClient: ThorMasterApprovalClient
    private var wakeRecognizer: SpeechRecognizer? = null
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var wakeEnabled = false
    private var waitingForWakeCommand = false
    private var wakeButton: Button? = null

    private val languages = listOf("English", "Hindi", "Bengali")
    private val languageTags = listOf("en-IN", "hi-IN", "bn-IN")
    private val voiceStyles = listOf("Cinematic Deep", "Natural", "Crisp Assistant")

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
        addMessage("THOR", "Systems ready. Type a command or tap the microphone.")
        showBossSetupIfNeeded()
        processPendingApprovalIntent()
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

    override fun onStart() {
        super.onStart()
        if (firebaseReady) {
            FirebaseAuth.getInstance().addAuthStateListener(masterAuthStateListener)
        }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 18)
            setBackgroundColor(Color.rgb(7, 11, 20))
        }

        val title = TextView(this).apply {
            text = "T H O R"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 210, 255))
        }
        root.addView(title, matchWrap())
        ObjectAnimator.ofFloat(title, "alpha", 0.55f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL ANDROID ASSISTANT"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        root.addView(subtitle, matchWrap())

        root.addView(ThorHudView(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (142 * resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = 8 })

        language = Spinner(this)
        language.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages
        )
        root.addView(language, matchWrap())

        val offlineButton = makeButton("INSTALL OFFLINE AI (~586 MB)")
        offlineAiButton = offlineButton
        offlineButton.setOnClickListener { installOfflineAi() }
        root.addView(offlineButton, matchWrap())

        voiceStyle = Spinner(this)
        voiceStyle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceStyles)
        voiceStyle.setSelection(getSharedPreferences("thor_voice", MODE_PRIVATE).getInt("style", 0).coerceIn(0, voiceStyles.lastIndex))
        voiceStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                getSharedPreferences("thor_voice", MODE_PRIVATE).edit().putInt("style", position).apply()
                applyVoiceStyle()
            }
        }
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voiceRow.addView(voiceStyle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val previewVoice = makeButton("PREVIEW VOICE")
        previewVoice.setOnClickListener {
            val sample = when (language.selectedItemPosition) {
                1 -> "नमस्ते, मैं थॉर हूँ। मैं आपकी सहायता के लिए तैयार हूँ।"
                2 -> "নমস্কার, আমি থর। আমি আপনাকে সাহায্য করতে প্রস্তুত।"
                else -> "Hello. I am THOR. Systems are ready."
            }
            speak(sample)
        }
        voiceRow.addView(previewVoice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(voiceRow, matchWrap())

        val masterSetupButton = makeButton("MASTER MODE SETUP")
        masterSetupButton.setOnClickListener { showMasterModeSetupDialog() }
        root.addView(masterSetupButton, matchWrap())

        val managementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val memoryButton = makeButton("PERSONAL MEMORY")
        memoryButton.setOnClickListener { showPersonalMemoryManager() }
        val securityButton = makeButton("SECURITY CENTRE")
        securityButton.setOnClickListener { showSecurityCentre() }
        managementRow.addView(memoryButton, weightedButton())
        managementRow.addView(securityButton, weightedButton())
        root.addView(managementRow, matchWrap())


        val scroll = ScrollView(this)
        chat = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(225, 235, 245))
            setPadding(12, 16, 12, 16)
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        input = EditText(this).apply {
            hint = "Type a command..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(14, 10, 14, 10)
        }
        root.addView(input, matchWrap())

        val buttons = LinearLayout(this).apply {
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
        val wake = makeButton("WAKE: OFF")
        wakeButton = wake
        wake.setOnClickListener { toggleWakeWord(wake) }

        buttons.addView(send, weightedButton())
        buttons.addView(mic, weightedButton())
        buttons.addView(help, weightedButton())
        buttons.addView(wake, weightedButton())
        root.addView(buttons, matchWrap())

        setContentView(root)
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
    }

    private fun processPendingApprovalIntent() {
        if (!bossProfile.isConfigured()) return
        val requestId = intent.getStringExtra("thor_approval_request_id") ?: return
        if (requestId.isBlank()) return
        intent.removeExtra("thor_approval_request_id")
        window.decorView.post { reviewMasterApproval(requestId) }
    }

    private fun startVoiceInput() {
        val tag = languageTags.getOrElse(language.selectedItemPosition) { "en-IN" }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to THOR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // A preference only: the installed speech service may still require internet.
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
        }
        try {
            startActivityForResult(intent, 1001)
        } catch (_: ActivityNotFoundException) {
            addMessage("THOR", "No speech recognition app is available. Please type your command.")
        }
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
            lower in listOf("revoke master sessions", "manage master sessions") -> showActiveMasterSessions()
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
                    putExtra("android.speech.extra.PREFER_OFFLINE", true)
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

    private fun askGemini(prompt: String) {
        if (!firebaseReady) {
            askLocalOrFallback(prompt)
            return
        }
        val languageInstruction = when (language.selectedItemPosition) {
            1 -> "Reply in natural Hindi."
            2 -> "Reply in natural Bengali."
            else -> "Reply in clear English."
        }
        val styleInstruction = "Automatically match the user's language and level of informality in every reply. If the user writes in casual English, internet slang, Bengali colloquial language, or Bengali-English mix, reply naturally in that same casual register and language. Understand common everyday slang and informal spellings; do not lecture the user about slang or unnecessarily correct it. Use slang naturally rather than forcing it into every sentence. Keep replies clear and avoid hateful slurs or targeted harassment."
        android.util.Log.i("THOR_GEMINI", "Gemini request started")
        addMessage("THOR", "Thinking... (up to 60 seconds)")
        mainScope.launch {
            try {
                val answer = GeminiAssistant.ask("$languageInstruction\n$styleInstruction\n${learningMemory.systemContext()}\nUser: $prompt")
                android.util.Log.i("THOR_GEMINI", "Gemini request succeeded")
                addMessage("THOR", answer)
                speak(answer)
            } catch (error: Exception) {
                android.util.Log.e("THOR_GEMINI", "Gemini request failed", error)
                val detail = error.message ?: error.javaClass.simpleName
                addMessage("THOR", "Online AI unavailable ($detail). Switching to the on-device AI.")
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
        "• pause music / next song / previous song / volume up\n" +
        "• wifi settings / bluetooth settings\n" +
        "• what time is it\n" +
        "• search space news\n" +
        "• teach me that no cap means I am serious\n" +
        "• what have you learned / forget learning X / clear learned memory\n" +
        "• install offline AI for open-ended offline chat\n\n" +
        "The offline language model is bundled into the APK build (~586 MB). Reminder notifications require notification permission; Android battery settings may delay them. Hindi/Bengali speech recognition depends on the installed speech service."

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
        stopWakeWord()
        wakeButton?.text = "WAKE: OFF"
        super.onPause()
    }

    override fun onDestroy() {
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
