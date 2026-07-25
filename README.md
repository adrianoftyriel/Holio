# Holio

A small Android game inspired by [hole.io](https://hole.io) — you control a hole
that roams a map and swallows everything it's big enough to eat. Each object you
swallow makes the hole a little bigger, so you can move on to larger prey. Rack
up as many points as you can before the timer runs out.

**No ads. No tracking. No network access at all.**

This is the first, deliberately simple version: one map, single player. More
maps, obstacles, opponents and modes can come later.

## Gameplay

- **Move:** touch and drag anywhere on screen — a floating joystick appears
  under your finger and steers the hole.
- **Swallow:** drive the hole over objects smaller than it. They tip in, you
  score, and the hole grows.
  - Bushes → Trees → Cars → Houses, in roughly increasing size and value.
- **Goal:** score as high as you can in the 2-minute round.
- **Restart:** tap the screen on the "Time's Up!" screen to play again.

The map is the same every round (fixed seed), so you can learn it and improve.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/org/holio/game/
│   ├── MainActivity.kt   – fullscreen host activity
│   ├── GameView.kt       – SurfaceView, input, lifecycle
│   ├── GameThread.kt     – the ~60 FPS game loop
│   ├── GameWorld.kt      – all game state, map generation, update logic
│   ├── Renderer.kt       – top-down 2D drawing
│   ├── Hole.kt           – the player hole (movement + growth)
│   ├── Prop.kt           – swallowable objects and their types
│   └── Joystick.kt       – floating on-screen joystick
└── res/                  – icon, theme, strings
```

The game is pure Android SDK (Kotlin + Canvas) — no third-party game engine and
no ad/analytics libraries.

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
- Orientation is landscape.
