# Grid Imposer (Android)

Android version of `add_grid_and_impose_line.py` / `add_grid_and_impose_grid.py`.

1. Tap **Choose PDF** (or use *Share → Grid Imposer* from any app).
2. Pick **Lines** or **Grid**, adjust the spacing in mm if you like. Optionally tick
   **Remove duplicate pages**, or choose **Dedupe only** to skip the ruled pages entirely (see below).
3. Tap **Create PDF**, choose where to save it. Then **Open** or **Share** it.

Every page of the input becomes one sheet twice as wide: original page on the left,
ruled page on the right, 12 pt apart, each with a 1 pt grey (0.8) outline. Sheet width is
2 x page + 12 pt, so A4 portrait comes out a hair wider than A3 landscape. The original page is embedded as
vector content, so text stays sharp. Rotated pages and crop boxes are handled.

Defaults: Grid = 5 mm squares, Lines = 5 mm spacing. Rule colour and 0.3 pt width match the scripts.

## Remove duplicate pages

Optional checkbox, off by default. Port of the `pymupdf` dedupe script and applied *before*
the ruled pages are added: pages without a page label are always kept, and among pages that
share the same label only the last one is kept. If the PDF has no page labels at all,
nothing is removed and the app says so when it finishes.

**Dedupe only** (third option next to Lines and Grid) does just this and nothing else: no ruled
pages, no wider sheets, page size and content unchanged. Like the original script, the output has
no page labels (they would be wrong after pages are removed). Output name ends in `_unique.pdf`.

## Build the APK

**Android Studio:** File -> Open this folder, wait for Gradle sync, press Run
(or Build -> Build APK). Needs Android Studio Koala (2024.1) or newer.

**Command line** (JDK 17 + Android SDK with platform 34, `ANDROID_HOME` set):

    ./gradlew assembleDebug
    # -> app/build/outputs/apk/debug/app-debug.apk

**No local setup:** push this folder to a GitHub repo. The workflow in `.github/workflows/`
builds the APK on every push (Actions tab -> latest run -> Artifacts).

Install the APK on your phone (allow "install unknown apps" for your browser/file manager).

**Signing:** debug builds are signed with the fixed key in `app/debug.keystore` (committed on
purpose; its password is the standard `android`). Every build, local or from GitHub Actions, therefore
has the same signature and installs over the previous one. Make sure the file is committed. It is for
personal sideloading only, never for publishing. If you installed an APK signed with a different
key earlier, uninstall it once first.

Uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0). Requires Android 8.0+.
