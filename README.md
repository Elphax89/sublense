# SubLens

**Live screen translation for Android, Windows and Mac.** A transparent lens you drag over YouTube subtitles, chats or any other text on screen. The translation appears right on top of the original, in its colours and at roughly its size. Reply in the other person's language with one tap. And on holiday, point the camera at a menu, a sign or the QR code on the table.

Free and open source. Works fully offline – no account, no ads, nothing leaves your phone. If you like, switch on **online translation with AI** (your own free key from GLM, Gemini, Groq …) for much more natural chats, slang and Chinese.

![SubLens on a phone: subtitles translated in place, a reply copied in Japanese, a Chinese menu translated with the camera, and a restaurant's ordering page translated after scanning its QR code](docs/screenshots.jpg)

🎬 [Watch the 1-minute demo](docs/demo.mp4)

## ⬇️ Download

### 👉 [**Download SubLens.apk**](https://github.com/Elphax89/sublense/releases/latest/download/SubLens.apk)

Download page with instructions: **https://elphax89.github.io/sublense/**

1. Open the link on your Android phone and open the downloaded file.
2. The first time, Android asks whether your browser may install apps → **Allow**.
3. Tap **Install**. If Play Protect warns you, tap *Install anyway* (the app isn't from the Play Store).
4. Open SubLens, allow **Display over other apps**, then tap **Start translator**.

> **Switch greyed out ("Controlled by restricted setting")?** Android protects apps from outside the Play Store this way. Unlock it once: *Settings → Apps → SubLens → ⋮ (top right) → Allow restricted settings*, then turn on *Display over other apps*. [Google's guide](https://support.google.com/android/answer/12623953)

**Updates:** SubLens checks for new versions by itself. The button at the bottom of the app turns into *New version available – install now?*. One tap downloads and installs it, and your settings are kept. The first time, Android asks you to let SubLens install apps.

## 💻 Windows and Mac

SubLens also runs on your computer: the same lens on top of any window – subtitles, chats, websites, games.

| | Download |
|---|---|
| **Windows 10/11** | [**Installer (.exe)**](https://github.com/Elphax89/sublense/releases/download/desktop-latest/SubLens-Setup-Windows.exe) · [Portable, no install (.exe)](https://github.com/Elphax89/sublense/releases/download/desktop-latest/SubLens-Portable-Windows.exe) |
| **macOS** (Apple Silicon, M1 or newer) | [**SubLens-macOS-arm64.dmg**](https://github.com/Elphax89/sublense/releases/download/desktop-latest/SubLens-macOS-arm64.dmg) |

- **Windows:** SmartScreen may warn because the app isn't signed – *More info → Run anyway*.
- **macOS:** drag SubLens to Applications. The first time, right-click SubLens → *Open* (or *System Settings → Privacy & Security → Open Anyway*), then allow **Screen Recording** for SubLens and start it again.
- Show or hide the lens with **Ctrl+Shift+L** (⌘⇧L on a Mac), or from the tray / menu bar icon.
- The lens is invisible to screen capture, so it never reads its own translations and never flickers. Drag ✥ to move it and the corner to resize it; ▶ switches between chat and video mode; the reply line copies your answer in the chat's language.
- Text recognition with Tesseract, translation with Meta's NLLB‑200 model (about 900 MB, downloaded once) – everything runs on your computer. Online translation with AI works here too, with the same services and keys as on the phone.

## Features

- 🔍 **Translation right in the lens.** Every text block is replaced where it is, in the colours of the original. Longer translations grow sideways instead of shrinking.
- 💬 **Subtitles, chats, any text.** Works on top of YouTube, Telegram, WeChat, websites, games and other apps.
- ✍️ **Reply line.** Type in your own language and tap copy. The translation into the chat's language is on your clipboard, ready to paste.
- 🎬 **Chat mode and video mode.** In chat mode (default) SubLens ignores GIFs, animated emojis and profile pictures, so translations stay perfectly still. Tap ▶ on the lens for video mode: moving pictures are read continuously so subtitles keep up.
- 📷 **Camera translation.** Point the camera at a menu, sign or poster: translations appear live in the picture. The shutter takes a sharp photo to read calmly, tap a dish to see the original, copy all of it. Works with photos from your gallery too, or share a picture to SubLens from any app.
- 🔳 **QR codes.** SubLens spots QR codes in the camera. *Open* opens the link; *Open + translate* starts the lens first, so a restaurant's ordering page in Chinese shows up in your language and you can order.
- 👁️ **Preview.** Shows the original text until you tap it again. Tapping a single translation shows its original for 3 seconds.
- 🔲 **A lens you can move and resize.** Always full screen width, in portrait and landscape. Drag the top or bottom handle to change the height (from 1/5 of the screen up to nearly all of it), drag the ↕ button to move it. ▶ ⇅ ⚙ ✕ sit in the bottom right corner; ⇅ moves the reply line above or below the lens when there is room.
- 🌍 **50+ languages** with automatic detection, and a ⇅ button to swap From and To. Chinese in traditional or simplified characters, and SubLens converts directly between the two.
- 🎨 **Your lens colour.** Pick one of nine colours for the frame and handles.
- 📴 **Offline.** Text recognition, language detection and translation run on the phone (Google ML Kit). Each language pack downloads once (about 30 MB), and you can download any pack by hand, for example before a trip.
- 🤖 **Online with AI (optional).** An AI service translates the whole screen in one go and understands the conversation – much more natural for chats, slang and Chinese. Bring your own key; several services are free. See [Online translation](#-online-translation-optional) below.
- 🔒 **Private.** No server of ours, no account. Screen frames and camera pictures only live briefly in memory and are never saved. With online translation, only the recognised text goes to the service you chose.
- 🌐 **The app speaks your phone's language:** English, German, Spanish, French, Italian, Portuguese, Polish, Turkish, Russian, Chinese (simplified and traditional), Japanese and Korean.

## 🤖 Online translation (optional)

Offline translation is good for subtitles and short texts. For chats it often sounds stiff, and Chinese suffers most. With online translation, an AI model gets all texts of the screen at once (plus the last few lines as context) and translates them like a person would – including your replies in the reply line, and the camera.

**Set it up in two minutes:** home screen → *Translation* → *Online with AI* → pick a service → **Get key ↗** (opens the provider's key page) → copy the key → **Paste key**. SubLens recognises which service a key belongs to, tests it with a sample sentence and picks a current model by itself. Or just tap **📋 Paste key from clipboard** at the top.

| Service | Cost | Good to know |
|---|---|---|
| **GLM · Z.AI** (Zhipu AI) | Free (GLM Flash models) | Very good at Chinese |
| **DeepSeek** | Very cheap, needs a small top-up | Excellent for Chinese and chat language; servers in China |
| **Qwen · Alibaba Cloud** | Free starter quota for new accounts | Strong in Chinese; choose the Singapore region – keys only work in their region |
| **Gemini · Google** | Free with a daily limit | Good in many languages; on the free tier Google may use texts to improve its products |
| **Groq** | Free with limits | Very fast, open models (Llama, Qwen …) |
| **OpenRouter** | Free models with a daily limit | One key for many models |
| **OpenAI-compatible** | – | Any compatible service, or your own server such as Ollama or LM Studio |

- **Your key stays yours.** It is stored encrypted (Android Keystore on the phone, the system key store on Windows and Mac) and only sent to its own service. There is no SubLens server in between.
- **Only text is sent** – never screenshots or photos. Lines that were translated before come from a cache, so only new text goes out.
- **Falls back to offline by itself** when there is no internet, the limit is reached or the key stops working – the lens says so once, then keeps translating offline.
- Model names change often, so SubLens reads each service's current model list and picks a suitable fast model; you can change it on the service's card.

## How to use it

**Subtitles:** open YouTube, turn on captions (CC) and move the lens over them. The translation replaces the subtitle.

**Chats:** put the lens over the messages. To answer, tap the reply line under the lens, write in your language, tap the copy button (or *Done* on the keyboard) and paste into the chat.

**Camera:** tap *📷 Camera* on the home screen. At the top choose From and To; for a Chinese menu pick *Auto – Chinese* so SubLens reads Chinese characters. Point at the text, or tap the shutter for a sharp photo. If a QR code is in view, a card with *Open* and *Open + translate* appears.

**Stop:** ✕ on the lens or *Stop* in the notification.

Android asks for screen sharing every time SubLens starts. Choose **Entire screen**, not a single app, or the lens won't line up with the text.

## Settings

| Area | Options |
|---|---|
| Language | From (automatic or fixed), To, swap ⇅, script for automatic detection, reply language (automatic = detected language) |
| Translation | On the phone (offline) or online with AI; service, key and model in the catalog |
| Lens | Colour, translation in the lens or in a bar next to it, bar position, text size, show original, video mode, reply line (above or below: ⇅ on the lens), refresh (fast 0.3 s · normal 0.5 s · battery saver 1 s) |
| Offline language packs | Wi-Fi only, download any language, delete packs |

The Windows and Mac panel uses the same order: Language · Translation · Lens · Offline translation model.

## Good to know

- **Android 8 or newer.** iOS doesn't allow overlays on top of other apps.
- **Brief flicker.** When something changes under the lens, SubLens hides the affected translations for a split second to read the screen again. Only the part that moved is re-read; everything else stays still. In video mode this happens about every 0.6 s over a playing video. If that bothers you, show the translation in the bar instead.
- **Protected apps.** Netflix, Disney+ and banking apps block screen sharing, so SubLens sees nothing there.
- **Scripts.** Text recognition reads Latin, Chinese, Japanese, Korean and Devanagari script. Cyrillic, Arabic, Greek, Hebrew and Thai work as target languages only.
- **Traditional Chinese** output and telling traditional from simplified text need Android 10 or newer; older phones get simplified characters.
- Text recognition runs through Google Play services and downloads its model once on first start.

---

## For developers

Plain Kotlin, no extra frameworks. Every push to `main` runs [`build.yml`](.github/workflows/build.yml): it builds the APK, publishes it as the latest release (`SubLens.apk`) and deploys the download page from `docs/` to GitHub Pages (*Settings → Pages → Source: GitHub Actions*).

Build locally (JDK 17 + Android SDK): `./gradlew assembleRelease`, or open the folder in Android Studio. `./gradlew testReleaseUnitTest` tests online translation against a local mock service (CI runs it before every build).

Releases are signed with a private key that lives only in the repository's GitHub Secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_PASSWORD`), never in the code. If you fork this, create your own key with `keytool -genkeypair -keystore release.jks -alias sublens -keyalg RSA -keysize 4096 -validity 36500`, use the same password for store and key, and add both secrets (the keystore as `base64 -w0 release.jks`). Without them the workflow stops with a clear message; local `./gradlew assembleDebug` works with Android's debug key.

| File | Purpose |
|---|---|
| `MainActivity.kt` | Home screen, permissions, settings, language packs, updates |
| `OnlineActivity.kt` | Online translation catalog: services, keys (paste / recognise / test), model |
| `OnlineTranslator.kt`, `OnlineClient.kt`, `Prompt.kt` | Online translation: one request per screen, retries, pause and offline fallback; prompt and answer parsing |
| `Providers.kt`, `SecretStore.kt` | The services and how their models are picked; keys encrypted with the Android Keystore |
| `OverlayService.kt` | Foreground service, screen sharing, capture loop (re-reads only what changed; learns and ignores animations in chat mode) |
| `OverlayController.kt` | Overlay windows: lens, handles, ⚙/✕, quick settings |
| `LensLayer.kt` | Draws translations over the original text (one small window per block) |
| `TextPipeline.kt` | Text recognition → language detection → translation, with colours and positions |
| `ScreenCapturer.kt` | Reads the screen, crops the lens area, change detection |
| `ComposerWindow.kt` | Reply line: input, keyboard, preview |
| `ReplyTranslator.kt` | Translates your reply for the clipboard |
| `CameraActivity.kt` | Camera screen: live preview, photo, gallery/share, QR card, Open + lens |
| `CameraTranslator.kt` | Text recognition, QR codes, language detection and translation for one picture |
| `CameraOverlayView.kt` | Draws translations over the camera picture or photo |
| `BlockBuilder.kt` | Turns recognised text into blocks with size and colours (shared by lens and camera) |
| `ChineseScript.kt` | Simplified ↔ traditional Chinese |
| `Updater.kt` | Checks GitHub releases and installs updates |
| `Prefs.kt`, `Languages.kt` | Settings, language lists |

The desktop app lives in [`desktop/`](desktop): Electron, [Tesseract.js](https://github.com/naptha/tesseract.js) for text recognition and [Transformers.js](https://github.com/huggingface/transformers.js) with NLLB‑200 for translation. Online translation is in `desktop/src/online.js` (same services, prompt and rules as Android; `npm run test:online` checks it against a mock service). [`desktop.yml`](.github/workflows/desktop.yml) tests the engine and builds the Windows installer and macOS DMG into the `desktop-latest` release. Run it locally with `cd desktop && npm install && npm start`.

## License

[MIT](LICENSE) © Elphax89
