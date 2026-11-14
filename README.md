# THOR Android Assistant — starter project

This is a lightweight first-stage Android app starter for the Oppo A16 / ARM64 Android devices.

## Included
- Text command input
- Private on-device notes (`note <text>`, `notes`)
- Private on-device to-do list (`todo <text>`, `tasks`, `done <number>`)
- Subtle animated THOR title
- Speech input through the Android speech-recognition activity
- Android Text-to-Speech
- English, Hindi and Bengali language selection
- Open a matching installed app
- Open Settings
- Open the dialer (does not place a call)
- Open an SMS composer (does not send a message)
- Open the alarm UI for a specified 24-hour time
- Create a reminder by opening the Clock app with a label and time for user confirmation (`remind 07:30 Take my book`)
- Open an installed music app (`play music`) or search YouTube for a requested track (`play <song name>`)
- Web search using the browser

## Build without Android Studio
The project is designed to be edited on a phone and built by GitHub Actions.

1. Create a GitHub repository named `thor-android`.
2. Upload the contents of this folder, keeping the same directory structure.
3. Open the repository's Actions tab and enable Actions if asked.
4. Select **Build THOR APK** and press **Run workflow**, or push a commit to `main`.
5. Open the successful workflow run, scroll to **Artifacts**, and download `THOR-debug-apk`.
6. Extract the ZIP and install `app-debug.apk` on your Android phone.

## Termux editing setup
Install Termux from its official F-Droid or GitHub distribution. In Termux:

```sh
pkg update && pkg upgrade -y
pkg install git nano unzip gh -y
termux-setup-storage
cd ~/storage/downloads
unzip THOR-Android-Starter.zip
cd THOR-Android-Starter
```

Create a new **empty** GitHub repository named `thor-android` in your browser (do not add a README or license). Then, in Termux, run:

```sh
gh auth login
```

Choose GitHub.com, HTTPS, and the browser/device-code sign-in option. Complete sign-in on GitHub. Then run:

```sh
git init
git add .
git commit -m "Create THOR starter app"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/thor-android.git
git push -u origin main
```

Replace `YOUR-USERNAME` with your GitHub username. For later edits:

```sh
git add .
git commit -m "Update THOR"
git push
```

Never paste a password or access token into chat or commit it into the project.

## Device notes
- `minSdk 23` supports older Android releases; the app should run on Android 10/11 and newer.
- The speech-recognition activity is provided by the device's installed speech service. `PREFER_OFFLINE` is only a preference and does not guarantee offline recognition.
- For offline speech, download the required language packs in the phone's speech input settings if your installed engine offers them.
- Text-to-Speech voice availability depends on installed Android TTS voice data.
- This starter does not read private files, messages, call logs, or notifications. It does not have unrestricted phone access.
- Calling opens the dialer; SMS opens the compose screen. You review and confirm those actions yourself.
- The current upgrade branch adds local notes and tasks without cloud access. Gemini online AI still needs Firebase AI Logic setup and a Firebase app configuration file. Do not commit API keys, passwords, or App Check debug tokens.
- Do not place API keys, passwords, or tokens in source code or GitHub commits.
