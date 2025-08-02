# Simple AC Scan
Minimal Android app to scan local network for devices on port 57223 and fetch /device.xml.

## Notes
- This includes a stub Gradle wrapper script and properties but does **not** include `gradle-wrapper.jar`. 
  You need to either:
    * Run `gradle wrapper --gradle-version 8.1.1` locally to generate the missing wrapper files (requires Gradle installed), or
    * Let your CI bootstrap it as fallback (as in your workflow).
- To build locally: `./gradlew assembleDebug` (will download Gradle distribution if needed).
- App uses AppCompat-compatible MaterialComponents theme to avoid the runtime crash you saw.
- Hit the "掃描" button to scan the local subnet for devices on port 57223.
