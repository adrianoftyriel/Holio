# Holio

A small Android game inspired by [hole.io](https://hole.io) — you control a hole
that roams a map and swallows everything it's big enough to eat. Each object you
swallow makes the hole a little bigger, so you can move on to larger prey. Rack
up as many points as you can before the timer runs out.

**No ads. No tracking.** Network is used only for things you choose to trigger:
the **Update** button (checks GitHub Releases for a newer APK), loading a
**real-world level** (fetches map data from OpenStreetMap), and **local
multiplayer** (talks only to other phones on the same Wi-Fi — no servers). The
offline *Classic Field*, single player, needs no network at all.

The world is drawn in a **2:1 isometric** view: the ground is a diamond, the
hole is a pit on it, and props (bushes, trees, cars, houses) stand up off the
ground with shadows for a sense of depth. The camera starts zoomed in and eases
out as your hole grows. You're not alone — a few **AI opponents** roam the same
map, and a hole that's big enough can even swallow a smaller one.

## Screens

- **Main menu** — start the game (**Single Player**), open **Settings**, or
  **Update** to fetch the latest release APK.
- **Level picker** — **Single Player** opens a list of places to play:
  - **Classic Field** — the original offline procedural map.
  - **Real-world levels** — actual locations pulled live from OpenStreetMap and
    turned into things to eat: trees, bushes and street furniture become small
    prey, buildings become the big prize. Bundled picks: **Central Park** (NY),
    **MIT campus**, **Mont-Saint-Michel**, **Golden Gate Park** (SF), **Hyde
    Park** (London), **Stanford Quad**, and **Venice**. The public map servers
    are often busy, so the loader retries across several Overpass mirrors; if
    they're all unreachable, the picker says so and you can choose Classic.
- **Settings** (from the menu) — choose the round length (1:00, 2:00 or 3:00);
  the choice is saved and used for every new round.
- **Local Multiplayer** — play others on the same Wi-Fi (see below).
- **In-game settings** — the ⚙ gear button (top-left) pauses the round and lets
  you **Resume**, **Restart Level**, or **End Level** (back to the main menu).
  In a multiplayer game the shared round can't be paused, so the gear just
  offers **Resume** or **Leave Game**.

## Gameplay

- **Move:** touch and drag anywhere on screen — a floating joystick appears
  under your finger and steers the hole.
- **Swallow:** drive the hole over objects smaller than it. They sink in, you
  score, and the hole grows.
  - Bushes → Trees → Cars → Houses, in roughly increasing size and value.
- **Compete:** three AI opponents eat props alongside you, each tracked on the
  live scoreboard (top-right). Grow bigger than a rival and roll over it to
  swallow it — you steal some of its score and it respawns small. Get caught by
  a bigger hole and the same happens to you.
- **Zoom:** the view starts tight and pulls back as you grow, so you always have
  room to manoeuvre.
- **Size bar:** the bar at the bottom shows how big your hole is, with a growing
  hole icon and a fill that tops out when you're big enough to swallow the
  largest thing on the map.
- **Goal:** finish the round with the highest score. The round ends when the
  timer runs out **or** when there's nothing left on the map to swallow —
  whichever comes first — and the final standings show where you placed.
- **Restart:** tap the "results" screen to play again, or use the gear to
  restart or end the level at any time.

The map is the same every round (fixed seed), so you can learn it and improve.

## Local multiplayer

Play with people on the **same Wi-Fi network** — no internet, no accounts, no
servers. From the main menu tap **Local Multiplayer**, then:

- **Host Game** — your phone becomes the game host. The lobby shows your LAN
  address (for manual joins), the players as they connect, and a **Bots** stepper
  so you decide how many AI opponents fill out the match. Tap **Start** when
  ready.
- **Join Game** — hosts on your Wi-Fi are discovered automatically (via NSD /
  mDNS); tap one to join. If discovery doesn't work on your network, tap
  **Enter IP…** and type the address shown on the host's lobby screen.

The host runs the authoritative simulation; every other phone streams its
joystick input up and renders the world the host sends back (~20 updates/sec
over TCP). Holes can swallow each other in multiplayer just like the bots do.

> **First cut.** The networking was written without a two-device test rig, so
> expect to shake out rough edges on real hardware — discovery quirks on some
> routers, reconnect handling, and latency tuning. Manual **Enter IP** is the
> reliable fallback if auto-discovery misbehaves.

### Updating in-game

Tap the **Update** button on the main menu. The app asks GitHub for the latest
release and, if it's newer than what you have installed, offers to download and
install it. Because every build is signed with the same checked-in debug
keystore, updates install cleanly over previous versions.

> **Requires public releases.** GitHub does not allow anonymous downloads of
> release assets from a **private** repository, and the app deliberately embeds
> no access token. While the repo is private the button will report
> *"Couldn't reach releases."* Make the repository public (Settings → General →
> Danger Zone → Change visibility) for the updater to work.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/org/holio/game/
│   ├── MainActivity.kt   – fullscreen host activity
│   ├── GameView.kt       – SurfaceView, screen flow (menu/game), input, lifecycle
│   ├── GameThread.kt     – the ~60 FPS game loop
│   ├── GameWorld.kt      – all game state, map gen, AI opponents, update logic
│   ├── Renderer.kt       – isometric drawing, zoom, scoreboard + menu screens
│   ├── Level.kt          – level catalogue (Classic + real-world OSM places)
│   ├── OsmLevelLoader.kt – fetches OpenStreetMap data and builds a level
│   ├── Scene.kt          – read-only view the Renderer draws (world or client)
│   ├── ClientScene.kt    – client-side world, populated from host snapshots
│   ├── GameServer.kt     – multiplayer host: TCP + NSD + state broadcast
│   ├── GameClient.kt     – multiplayer client: NSD discovery + snapshot apply
│   ├── Hole.kt           – a hole (player, remote human, or AI)
│   ├── Prop.kt           – swallowable objects and their types
│   ├── Joystick.kt       – floating on-screen joystick
│   ├── Settings.kt       – persisted player settings (round length)
│   └── Updater.kt        – "check GitHub Releases & install" updater
└── res/                  – icon, theme, strings, FileProvider paths
```

The game is pure Android SDK (Kotlin + Canvas) — no third-party game engine and
no ad/analytics libraries. The isometric look is a 2:1 projection applied at
draw time; the simulation itself still runs in a flat top-down world plane.

## Building

You need the Android SDK installed. Point Gradle at it either by setting
`ANDROID_HOME` / `ANDROID_SDK_ROOT`, or by creating a `local.properties` file:

```
sdk.dir=/path/to/Android/sdk
```

Then build a debug APK:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```
./gradlew installDebug     # to a connected device/emulator
```

- **minSdk:** 24 (Android 7.0)
- **targetSdk / compileSdk:** 34
- Orientation: portrait or landscape — the HUD and camera adapt as you rotate.

## Releases (CI)

Every push to `main` (including merged pull requests) runs the
[`Build and Release APK`](.github/workflows/release.yml) GitHub Actions workflow,
which:

1. builds the APK with the Android SDK on a hosted runner,
2. attaches it as a workflow artifact, and
3. publishes a GitHub Release tagged `v1.0.<run-number>` with the APK attached.

The APK is **debug-signed**, so it installs on any device without extra setup.
Producing a Play-Store-ready *release*-signed APK later just means adding a
keystore (stored as encrypted repository secrets) and a `signingConfig` in
`app/build.gradle.kts` — no other changes to the pipeline are needed.

## Attribution

Real-world levels are built from data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, available under the Open Database License (ODbL). The data is
fetched on demand from the public [Overpass API](https://overpass-api.de/) only
when you choose a real-world level.
