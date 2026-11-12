package com.thor.assistant

import android.app.Activity
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var localStore: ThorLocalStore
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var firebaseReady = false

    private val languages = listOf("English", "Hindi", "Bengali")
    private val languageTags = listOf("en-IN", "hi-IN", "bn-IN")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localStore = ThorLocalStore(this)
        initializeFirebase()
        tts = TextToSpeech(this, this)
        buildScreen()
        addMessage("THOR", "Systems ready. Type a command or tap the microphone.")
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
        help.setOnClickListener {
            addMessage("THOR", helpText())
        }

        buttons.addView(send, weightedButton())
        buttons.addView(mic, weightedButton())
        buttons.addView(help, weightedButton())
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
                val number = command.substringAfter(' ').filter { it.isDigit() || it == '+' }
                if (number.isNotBlank()) {
                    launchIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")),
                        "Opening your SMS app. Review and send the message yourself.")
                } else {
                    addMessage("THOR", "Use: sms followed by a phone number.")
                }
            }

            
lower.startsWith("alarm ") ||
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

            else -> {
                respond(
                    "I can handle basic phone commands offline. Online AI chat will be added in the next stage.",
                    "मैं अभी कुछ बुनियादी फोन कमांड ऑफलाइन कर सकता हूँ। ऑनलाइन AI अगले चरण में जोड़ा जाएगा।",
                    "আমি এখন কিছু সাধারণ ফোন কমান্ড অফলাইনে করতে পারি। পরের ধাপে অনলাইন AI যোগ হবে।"
                )
            }
        }
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
            addMessage("THOR", "Online AI is not ready. Check Firebase setup and try again.")
            return
        }
        val languageInstruction = when (language.selectedItemPosition) {
            1 -> "Reply in natural Hindi."
            2 -> "Reply in natural Bengali."
            else -> "Reply in clear English."
        }
        addMessage("THOR", "Thinking...")
        mainScope.launch {
            try {
                val answer = GeminiAssistant.ask("$languageInstruction\n\nUser: $prompt")
                addMessage("THOR", answer)
                speak(answer)
            } catch (error: Exception) {
                addMessage("THOR", "Gemini couldn't answer: ${error.message ?: "Please check your connection and Firebase App Check setup."}")
            }
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



    private fun helpText(): String =
        "Try these commands:\n" +
        "• open YouTube\n" +
        "• open Settings\n" +
        "• call 9876543210 (opens dialer; you confirm)\n" +
        "• sms 9876543210 (opens SMS app)\n" +
        "• alarm 07:30\n" +
        "• what time is it\n" +
        "• search space news\n\n" +
        "Hindi/Bengali recognition depends on the installed speech service."

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
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "thor-${System.currentTimeMillis()}")
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) setTtsLanguage()
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mainScope.cancel()
        super.onDestroy()
    }
}
