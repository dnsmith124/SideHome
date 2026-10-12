# SideHome Pixel Edition

**A calm, ambient home-screen launcher for the Google Pixel 9 — a window onto your day, not a wall of apps.**

A fork of [SideHome](https://github.com/side-suite/SideHome) by Oliver Palonkorpi, which was built for
the Sidephone SP-01 keypad phone. This edition keeps its design and its ethos — a short list of
favourites over a day-following sky, no widgets, no icon grid, no shortcuts, three permissions — and
fits it to a tall touch phone.

SideHome Pixel Edition is free software under the GNU GPL-3.0, like the original.

---

## What it does

- **A day-following ambient sky.** The background is one continuous OKLCH colour field that tracks
  the sun for your location and drifts through dawn → day → dusk → night. Static between shifts, so
  an idle home costs almost nothing.
- **A calm home page.** A large clock, the date with a one-line weather glance, and up to ten
  favourites as a text-and-glyph list. Tap the clock to open the clock app.
- **Direct-manipulation editing — no edit mode.** Long-press a favourite to pick it up and place it;
  drop one onto another to make a folder; drag it off to remove.
- **An app library that's a place, not a sheet.** Swipe up. Search, or browse an A–Z list or grouped
  category bubbles — your choice, remembered. It can open ready-to-scroll (the default) or
  ready-to-type ("Library opens in", in Settings).
- **A weather page.** Swipe left for a calm forecast for a place you type: now, a change-line, the
  hours, sunrise and sunset, and the week. One switch removes the page entirely. *(Opt-in.)*
- **A left page, your choice.** Swipe right for one of two quiet pages, picked in Settings:
  - **Now** — the hour's name, how much daylight is left, sunrise and sunset, your battery, and the
    next alarm. Every line is absent unless it would tell you something.
  - **Notes** — a short list you keep: a line of journal, a reminder. Tap to tick it off, hold to
    remove it. No alarms, no notifications; a note is something you'll see the next time you swipe.
- **Hidden and Locked shelves.** Hold an app in the library and choose **Hide** or **Lock**. It leaves
  the list, the search, the bubbles and the home, and sits on a shelf at the foot of the library.
  Hidden opens on a tap. Locked opens only after Android's own screen-lock screen confirms your PIN,
  pattern or password — the launcher never sees it and keeps no secret of its own. Hold an app on a
  shelf to bring it back. To shelve several at once, tap the select button beside the search field,
  mark the apps, and use the bar at the foot of the list.
- **Six themes.** Long-press an empty patch of home to zoom out and swipe through Ambient, Full light,
  Full dark, Edge, Custom (your own hue and saturation) and Photo (a picture of your own, framed
  against a live ghost of your home).
- **Notification dots** and **wallpaper sync**, both opt-in, as upstream.

## What changed from SideHome

- Everything about the keypad is gone: hardware key navigation, the 1–9 quick-launch numbers, the
  D-pad hints and the SideType keyboard integration. This is a touch launcher.
- The clock and date are larger and the home holds up to ten favourites, sized for a 6.3" screen.
- The left page exists, with the Now and Notes pages above; upstream shipped it disabled.
- The library has Hidden and Locked shelves; the long-press menu grew Hide and Lock cells, and a
  select button beside the search field marks many apps for shelving at once.
- The library opens quiet by default, so the keyboard doesn't rise over the list on every open.
- Its own app id (`io.github.dnsmith124.sidehome`), so it installs beside upstream SideHome.

Not changed, on purpose: the weather location is still **typed, not sensed**. There is no location
permission, and there won't be.

## Install

Build it (below), or grab an APK from this repository's Releases when there is one, then:

```
adb install -r app-release.apk
```

Press **Home** and choose **SideHome Pixel Edition** as your default launcher. It needs Android 12
(API 31) or later and is laid out for the Pixel 9's 1080×2424 screen.

## First-time setup

- **On first launch**, a one-time coach shows the swipes. It appears once and never again.
- **Weather (optional):** swipe left and tap **"Set your location"** to type a city. Nothing is
  fetched until you do.
- **Left page:** Settings → **Left page** → Off, Now or Notes.
- **Notification dots (optional):** Settings → **Notification dots** → grant access on Android's own
  page.
- **Wallpaper sync (optional):** the first time it could apply, the launcher asks once whether the
  system and lock wallpaper should follow the theme.

## Privacy & permissions

No tracking, no ads, no accounts. Exactly three permissions, each tied to a feature you opt into:

- **`INTERNET`** — for the weather, and only the weather: two requests to
  [Open-Meteo](https://open-meteo.com), on view, when the forecast is stale. Nothing is fetched until
  you set a location.
- **`SET_WALLPAPER`** — for wallpaper sync, and it asks first. The permission can only *set* a
  wallpaper, never read one.
- **Notification access** — optional, off by default, for the dots. The listener keeps a set of
  package names in memory and nothing else.

The Now page reads the battery (a public sticky broadcast) and the next alarm (public to every app);
neither needs a permission. Notes and the shelves are stored in the launcher's own preferences on the
phone and never leave it. The Locked shelf asks the keyguard to confirm your screen lock, which needs
no permission either.

## For developers

Native Jetpack Compose in Kotlin — a fresh `HOME` Activity, not a Launcher3 fork. `minSdk 31`,
`compileSdk 36`, AGP 9 with Gradle 9.4. The Kotlin namespace stays `fi.palonkorpi.sidehome` so merges
from upstream stay cheap; only the app id differs.

Build and install a debug APK (from the `android/` directory, with a JDK 21 — Android Studio's bundled
one works):

```
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:installDebug
```

The debug build has its own app id (`…sidehome.debug`) and label ("SideHome PE dev"), so it installs
beside a release build. `local.properties` with `sdk.dir` is gitignored; create it if the build can't
find the SDK.

Run the unit tests (no device needed):

```
./gradlew :app:testDebugUnitTest
```

The pure logic — colour, gestures, drop rules, weather, the Now page's lines, the notes model — is
unit-tested; layout is not.

## License

GNU General Public License v3.0 — see [`LICENSE`](LICENSE). SideHome is © Oliver Palonkorpi; this
fork's changes are released under the same license.

## Credits

- [SideHome](https://github.com/side-suite/SideHome) and the whole SideSuite, by Oliver Palonkorpi.
- Weather by [Open-Meteo](https://open-meteo.com); icons from the [Iconoir](https://iconoir.com) set
  (MIT License); the [Inter](https://rsms.me/inter/) typeface.
