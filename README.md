# Miqat

Prayer times and qibla for an Android phone and a Wear OS watch (built for a Pixel Watch). Kotlin + Jetpack Compose.

## Modules

| Module | What's in it |
|---|---|
| `core` | Plain Kotlin that runs on any JVM. Prayer-time math, calculation methods, country → method defaults, qibla bearing, settings model, reminder planning. Unit tested. |
| `platform` | Android code both apps share: settings store, phone↔watch sync, alarm scheduling, notifications, location, compass sensor, haptics, the compass dial. |
| `mobile` | Phone app: Times / Qibla / Settings tabs. |
| `wear` | Watch app (times, combined compass + countdown, settings) and the complication. |

Both apps use the same application ID (`dev.miqat`) and the same signing key. The Wearable Data Layer needs both of those to pair them.

## Install

Build both APKs:

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

This Mac routes traffic through Cloudflare Zero Trust, so Gradle is pointed at a truststore that includes the Cloudflare CA (`~/.gradle/cloudflare-cacerts.jks`, set in `~/.gradle/gradle.properties`). Without it, dependency downloads fail with a PKIX error.

They end up in `mobile/build/outputs/apk/debug/mobile-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk`.

**Phone:** turn on Developer options and USB debugging, plug in, then:

```bash
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

**Watch (Pixel Watch):**
1. On the watch: Settings → System → About → Versions, tap *Build number* 7 times.
2. Settings → Developer options → turn on *ADB debugging* and *Wireless debugging*. Watch and Mac need to be on the same Wi-Fi.
3. Tap *Pair new device*. It shows an IP:port and a code.
4. On the Mac:

```bash
adb pair <ip>:<pair-port> <code>
```

```bash
adb connect <ip>:<port shown on the Wireless debugging screen>
```

```bash
adb -s <ip>:<port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Or open the folder in Android Studio, pick the `mobile` or `wear` run configuration, pick the device, and press Run.

**Add the complication:** long-press your watch face → Edit → tap a complication slot → *Miqat: Next prayer + Qibla*. It works in short, long and ranged (ring) slots.

The first time you open the phone app, allow location, then go to Settings → Reliability and set location to "Allow all the time". That's what lets times follow you when you travel without opening the app.

## How it avoids the usual prayer-app problems

**Reminders use plain alarms.** There's no persistent notification and no foreground service. Reminders are exact `AlarmManager` alarms (`setExactAndAllowWhileIdle`). They fire on the minute in Doze and wake the app even when its process is dead. The app keeps the next 16 alerts registered at a time. Every time one fires, the whole plan is recomputed from scratch, so the schedule can't empty out or drift. It's also recomputed on boot, clock or timezone change, app update, settings change and location change. A WorkManager job twice a day is the backstop.

**Exact alarms are granted automatically.** The app declares `USE_EXACT_ALARM`, which Android 13+ grants at install time with no prompt. (If this ever ships on the Play Store, Google limits that permission to alarm and calendar apps, so it would need `SCHEDULE_EXACT_ALARM` and a user prompt instead.) If exact alarms are ever revoked, it falls back to inexact alarms that still fire, just a few minutes late.

**Location costs almost nothing.** It doesn't use GPS. It asks the fused location provider for `LOW_POWER` updates (cell and Wi-Fi), delivered to a `PendingIntent` only after you've moved 3 km or more, batched up to 2 hours. That registration stays in place when the app is killed. It also grabs one fresh fix on timezone change and whenever you open the app. Coarse location is enough: prayer times move about 1 minute per 25 km.

**The watch complication never polls.** The complication sends the watch a *timeline*: one entry per prayer window for the next ~2 days. The watch face switches entries at each prayer by itself, and draws the countdown from a target time. So the watch app isn't woken every minute to update it. On the ring version, Wear OS 4+ animates the fill from a time expression too.

**Sync without polling.** Settings and location go through the Wearable Data Layer. Each change is pushed once, Google Play services delivers it even when the other app isn't running, and each device keeps whichever copy is newer.

**No duplicate alerts on the wrist.** The watch posts its own notifications and turns off bridging of the phone's copies. Each device has its own "Notify on this device" switch, so you can pick phone, watch or both.

## Calculation

The method is picked from the country you're in (reverse geocoded; if that fails offline it falls back to the timezone's region). You can override method and Asr madhab on either device.

| Where | Default |
|---|---|
| US, Canada, Mexico | ISNA (15°/15°) |
| Saudi Arabia, Bahrain, Yemen | Umm al-Qura (Isha 90 min, 120 in Ramadan) |
| UAE / Qatar / Kuwait | Dubai / Qatar / Kuwait |
| Egypt, Sudan, Libya, Syria, Lebanon, Iraq | Egyptian |
| Pakistan, India, Bangladesh, Afghanistan | Karachi + Hanafi Asr |
| Turkey | Diyanet + Hanafi Asr |
| Singapore, Malaysia, Indonesia, Brunei | MUIS/JAKIM/Kemenag (20°/18°) |
| Iran | Tehran |
| Algeria, Morocco, Tunisia, France, Russia | their national authorities |
| Everywhere else | Muslim World League |

The table is defined in `core/.../Methods.kt` (`RegionalDefaults`). Local practice varies within countries (the UK especially), so treat it as a starting point.

Above about 48° latitude in summer, Fajr and Isha can stop existing astronomically. The engine then caps them with the "angle-based" rule (same default as aladhan.com).

Tests compare against aladhan.com for New York, Makkah, London, Karachi, Cairo and Stockholm, within 1 minute.

## Qibla

The great-circle bearing to the Kaaba is shown against true north. The compass uses the fused rotation-vector sensor and corrects for magnetic declination at your location. It asks you to calibrate (figure-8) when the magnetometer reports low accuracy or the heading error estimate goes over 30°. One firm haptic tap when you come within 3° of the qibla; it re-arms after you turn more than 8° away.

## Known limits

- **A live compass inside the complication isn't possible on Wear OS.** Third-party complications are drawn by the watch face from data the app sends; they can't stream sensor readings. Apple's compass complication is first-party. Instead, the long complication shows the qibla bearing ("Qibla 58° NE") next to the countdown, and tapping any Miqat complication opens a full-screen live compass with the next-prayer countdown in the middle.
- If you force-stop the app, Android cancels its alarms until you open it again. That's Android's rule for every app.
- Without "Allow all the time" location, a timezone change gives you a notification asking you to open the app, since it can't fetch a new location in the background.
