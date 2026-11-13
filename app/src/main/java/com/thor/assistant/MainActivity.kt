package com.thor.assistant

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var language: Spinner
    private lateinit var voiceStyle: Spinner
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var localStore: ThorLocalStore
    private lateinit var offlineAi: ThorOfflineAi
    private var offlineAiButton: Button? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var firebaseReady = false
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
        offlineAi = ThorOfflineAi(applicationContext)
        initializeFirebase()
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("THOR", "Systems ready. Type a command or tap the microphone.")
        if (offlineAi.isModelInstalled()) {
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
                if (!offlineAi.isModelInstalled()) {
                    addMessage("THOR", "Downloading the offline language model (~586 MB). Wi-Fi is recommended. Keep THOR open until it finishes.")
                    offlineAi.downloadModel { downloaded, total ->
                        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread { button.text = "DOWNLOADING $percent%" }
                    }
                }
                button.text = "LOADING OFFLINE AI..."
                addMessage("THOR", "Model downloaded. Initializing the offline AI; first load may take a little while.")
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
        if (!offlineAi.isModelInstalled()) {
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
                val answer = offlineAi.ask(prompt)
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

        when {
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

            
lower.startsWith("alarm ") ||

            lower.startsWith("remind ") || lower.startsWith("reminder ") ||
                lower.startsWith("मुझे याद ") || lower.startsWith("মনে করিয়ে ") -> {
                // Format: remind 07:30 Take my book. The Clock app asks the
                // user to confirm; THOR does not silently schedule an alarm.
                val timeMatch = Regex("""(\d{1,2})[:.](\d{2})""").find(command)
                if (timeMatch == null) {
                    addMessage("THOR", "Use: remind 07:30 Take my book.")
                } else {
                    val hour = timeMatch.groupValues[1].toIntOrNull()
                    val minute = timeMatch.groupValues[2].toIntOrNull()
                    val reminderText = command.substring(timeMatch.range.last + 1)
                        .trim().trimStart('-', ':')
                        .ifBlank { "THOR reminder" }
                    if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                        addMessage("THOR", "That reminder time is not valid. Use 24-hour time.")
                    } else {
                        val reminder = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(AlarmClock.EXTRA_MESSAGE, reminderText)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        }
                        launchIntent(reminder, "Opening the Clock app to confirm your reminder: $reminderText")
                    }
                }
            }

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
                            } else if (normalized.contains("hey thor") ||
                                normalized == "thor" || normalized.startsWith("thor ")) {
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val button = wakeButton ?: return
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWakeWord(button)
            } else {
                addMessage("THOR", "Microphone permission is required for wake listening.")
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
                val answer = GeminiAssistant.ask("$languageInstruction\n$styleInstruction\n\nUser: $prompt")
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



    private fun helpText(): String =
        "Try these commands:\n" +
        "• open YouTube\n" +
        "• open Settings\n" +
        "• call 9876543210 (opens dialer; you confirm)\n" +
        "• sms 9876543210 (opens SMS app)\n" +
        "• alarm 07:30 or remind 07:30 Take my book\n" +
        "• hey THOR (optional wake listening while app is open)\n" +
        "• pause music / next song / previous song / volume up\n" +
        "• wifi settings / bluetooth settings\n" +
        "• what time is it\n" +
        "• search space news\n" +
        "• install offline AI for open-ended offline chat\n\n" +
        "The offline language model is a separate ~586 MB download. Hindi/Bengali speech recognition depends on the installed speech service."

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
        tts?.stop()
        tts?.shutdown()
        offlineAi.close()
        mainScope.cancel()
        super.onDestroy()
    }
}
