# PhoneBridge (Native Android Relay & In-App OTA Updater)

A high-reliability, lightweight (<1.5 MB) native Android relay system built with Kotlin and Android SDK. 
Designed for private dual-phone monitoring between a home phone (**Realme**) and a pocket phone (**Sony Xperia**).

---

## Features

- **SMS & OTP Extraction:** Auto-extracts 4-8 digit OTPs with 1-tap copy clipboard button.
- **Call & Missed Call Monitoring:** Instant alerts for incoming ringing and missed calls.
- **All-App Notification Forwarding:** Intercepts Gmail, bKash, Nagad, WhatsApp, and bank notifications.
- **Live Telemetry:** Real-time battery percentage, charging status (⚡), network type, and online status.
- **Zero-Config Cloud Relay:** Pre-configured pub/sub topic on `ntfy.sh` with sub-second latency and zero manual credential entry.
- **Automated CI/CD Pipeline:** GitHub Actions builds signed release APKs on every `git push`.
- **In-App 1-Tap OTA Updates:** Phones receive instant update alerts when new versions are pushed. Tap "Update" to download and install seamlessly in-place.
- **Identical Keystore Signing:** All releases are cryptographically signed with the same key (`app/keystore.jks`), preventing any `INSTALL_FAILED_UPDATE_INCOMPATIBLE` errors.

---

## Project Structure

```
phone_bridge_native/
├── .github/workflows/
│   └── build-and-release.yml    # Automated CI/CD build & release pipeline
├── app/
│   ├── keystore.jks             # Unified signing key for seamless updates
│   ├── build.gradle.kts         # ProGuard/R8 minification (1.45 MB APK)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/phonerelay/phonebridge/
│       │   ├── AppUpdater.kt             # In-app GitHub release checker & installer
│       │   ├── BridgeForegroundService.kt# Persistent 24/7 background service
│       │   ├── SmsReceiver.kt            # SMS & OTP regex extractor
│       │   ├── CallReceiver.kt           # Telephony call tracker
│       │   ├── AppNotificationListener.kt# NotificationListenerService
│       │   ├── FirebaseRelay.kt          # Relay dispatcher (ntfy & firebase)
│       │   ├── HostActivity.kt           # Realme Home Hub UI
│       │   └── ViewerActivity.kt         # Xperia Remote UI
│       └── res/
└── push_update.bat              # 1-click commit, push, and deploy script
```

---

## How to Deploy Updates

1. Make changes to the code.
2. Double-click `push_update.bat` or run:
   ```bash
   git add .
   git commit -m "Your update description"
   git push origin main
   ```
3. GitHub Actions will automatically compile the release APK, create a GitHub Release, and notify both phones.
4. On your phones, tap the update notification or click **Update** in PhoneBridge to install in 1 tap!
