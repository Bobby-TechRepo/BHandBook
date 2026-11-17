# Releasing Infinite Canvas Calculator

This is the path from a working checkout to a build you can give to other people or upload to Google Play. Steps marked **(you)** need Android Studio / your machine; everything else is already in the project.

## 0. Decide about the handwriting model first (blocker for a PUBLIC release)

The CoMER model files are **not** in the project or in the APK: their redistribution terms are not established (`THIRD_PARTY_COMER.md`), and `scripts/fetch_comer_models.py` only installs them on your machine for testing. Without them the app still works, using the built-in geometric recogniser, but recognition is much less accurate (it logs "primary recogniser is not usable in this build"). So before a public release you must either:

1. confirm the licence of the CoMER weights allows shipping them in an app (and then put them in `app/src/main/assets/models/comer/` before building; they are stored uncompressed on purpose), **or**
2. train / obtain a recogniser you may ship, **or**
3. ship the geometric recogniser only and say so honestly in the store listing.

A build for friends, family or your own phone can include the models you fetched; just do not publish it.

## 1. Version

`gradle.properties` holds `appVersionName` (shown to users) and `appVersionCode` (a whole number that must increase with every upload to Google Play). Or pass them for one build: `./gradlew bundleRelease -PappVersionName=1.0.1 -PappVersionCode=2`.

## 2. Signing key **(you, once)**

```
keytool -genkeypair -v -keystore release.jks -alias bhandbook -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.template keystore.properties      # then fill in the four values
```

`keystore.properties` and `*.jks` are git-ignored. **Back the key file and its passwords up somewhere safe** (not only on this computer). With Google Play App Signing (recommended when you upload an app bundle) this key is only your *upload* key and can be reset by Google if lost; for APKs you hand out yourself, losing it means users cannot update, only reinstall.

Without `keystore.properties` a release build is produced unsigned (Android Studio: Build > Generate Signed App Bundle / APK does the same with a wizard).

## 3. Build **(you)**

```
./gradlew testDebugUnitTest          # the unit tests
./gradlew assembleRelease            # APK for sideloading, in app/build/outputs/apk/release/
./gradlew bundleRelease              # app bundle for Google Play, in app/build/outputs/bundle/release/
python tools/check_release.py        # inspects the APK and R8's mapping file (see below)
```

What the release type does (`app/build.gradle.kts`): R8 shrinking and obfuscation are on (the `optimization` block); ONNX Runtime's classes are protected from renaming (`app/proguard-rules.pro`, because its native code finds them by name); only `arm64-v8a` and `armeabi-v7a` native libraries are packaged (phones; this roughly halves what ONNX Runtime adds); and the build is signed if `keystore.properties` exists.

### `tools/check_release.py`

It fails the build (exit status 1) when it finds something that only goes wrong after shrinking: an ONNX Runtime class renamed or removed in `mapping.txt` (recognition would silently fall back to the weak recogniser, or crash), a missing native library for a CPU type, a missing font or licence file, the INTERNET permission appearing (this app promises to make no network connections), or an APK over the size budget (`--max-mb`, default 60). It warns about missing model files, models stored compressed (slow to load), and unexpected CPU types. It is tested (`tools/test_check_release.py`) against synthetic APKs and mappings.

### If the build rejects the keep rules

`proguardFiles(...)` in the `release` block is the long-standing way to add keep rules; AGP 9 moved R8 settings into the `optimization` block, and I could not verify offline that 9.4.1 still accepts the old call. If Gradle sync reports an error on that line, replace it with the new form (or delete it and run `check_release.py`: current ONNX Runtime releases usually ship their own consumer rules, and the checker will tell you whether the names survived):

```kotlin
optimization {
    enable = true
    packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
    keepRules { files.add(file("proguard-rules.pro")) }      // new-DSL form: adjust to what Android Studio offers (Ctrl+Space)
}
```

## 4. Test the RELEASE build on a real phone **(you)**

Release builds behave differently from debug builds (R8, no debug code). Install the release APK (`adb install -r app-release.apk`) and run the "Release build" section of `docs/QA-CHECKLIST.md`. The two things that go wrong most often: recognition quietly falling back to the weak recogniser (check `adb logcat | grep -i comer` for "not usable"), and anything that depended on class names (none should).

## 5. Google Play **(you)**

* Create the app in the Play Console; upload the `.aab`; keep Play App Signing on.
* **Store listing:** use `docs/STORE_LISTING.md` (descriptions, the data-safety answers, content rating notes). Screenshots: take them on your phone (a column sum, an equation with steps, a graph, dark mode).
* **Privacy policy:** `docs/PRIVACY.md` is written for this app; Play requires a public URL, so publish that text on any page you control (a GitHub Pages page is enough) and paste the link.
* **Target SDK:** `targetSdk = 37` in `app/build.gradle.kts`; Play raises its minimum every year, so check when you upload.
* Upload R8's `app/build/outputs/mapping/release/mapping.txt` with each release (Play Console > App bundle explorer > Downloads > Upload deobfuscation file) so crash reports are readable.
* Start with an internal or closed test track, install from Play on a phone, and run the checklist again on that exact build.

## 6. After release

Android vitals in the Play Console report crashes and ANRs; the app has no crash reporter of its own (it would need a network). Keep every released `.aab`, its `mapping.txt` and the `release.jks` together. Update `CHANGELOG.md` and bump the version for each upload.

## Known limits for a first release

* The models question in step 0.
* Recognition quality on real handwriting still needs more samples than the synthetic tests provide: the opt-in sample capture (Settings) exists for this.
* Accessibility (TalkBack of the canvas), string resources for translation and a user guide are not done; tips and the checklist cover the basics.
