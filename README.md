# Grid Imposer (Android)

Android version of `add_grid_and_impose_line.py` / `add_grid_and_impose_grid.py`.

1. Tap **Choose PDF** (or use *Share → Grid Imposer* from any app).
2. Pick **Lines** or **Grid**, adjust the spacing in mm if you like.
3. Tap **Create PDF**, choose where to save it. Then **Open** or **Share** it.

Every page of the input becomes one sheet twice as wide: original page on the left,
ruled page on the right, 12 pt apart, each with a 1 pt grey (0.8) outline. Sheet width is
2 x page + 12 pt, so A4 portrait comes out a hair wider than A3 landscape. The original page is embedded as
vector content, so text stays sharp. Rotated pages and crop boxes are handled.

Defaults: Grid = 5 mm squares, Lines = 9.5 mm (27 pt) spacing. Rule colour and 0.3 pt width match the scripts.

## Build the APK

**Android Studio:** File -> Open this folder, wait for Gradle sync, press Run
(or Build -> Build APK). Needs Android Studio Koala (2024.1) or newer.

**Command line** (JDK 17 + Android SDK with platform 34, `ANDROID_HOME` set):

    ./gradlew assembleDebug
    # -> app/build/outputs/apk/debug/app-debug.apk

**No local setup:** push this folder to a GitHub repo. The workflow in `.github/workflows/`
builds the APK on every push (Actions tab -> latest run -> Artifacts).

Install the APK on your phone (allow "install unknown apps" for your browser/file manager).

Uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0). Requires Android 8.0+.
