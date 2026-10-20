# Trench Run

A tilt-controlled Android arcade prototype: fly a fighter down a long trench, dodging bridges, raised floors and walls while blasting drones.

## Install on your phone

1. On your Android phone, open the repo's **Releases** page and find **Trench Run prototype**.
2. Download `TrenchRun.apk` and open it. Allow installs from your browser if Android asks.
3. Every push to `main` rebuilds the APK automatically (see the **Actions** tab). New builds install over the old one.

## How to play

| Control | Action |
|---|---|
| Tilt left / right | Steer left / right |
| Tilt forward / back | Dive / climb (swap on the title screen) |
| Hold the **left** side of the screen | Fire lasers at the crosshair |
| Hold the **right** side of the screen | Raise the shield |

- Hold the phone however is comfortable, then tap to launch. That angle becomes "level".
- **Shield energy** is your lifeline. A hit costs 34 energy, or only 12 if your shield is raised. Raising it drains energy while held, and energy slowly rebuilds as you fly.
- **If you hit something with no shield energy left, the run is over.**
- **Cyan cells** restore 35 shield energy. **Orange cores** upgrade your lasers (Mk I to IV: twin shots, faster fire, more damage, triple shot).
- Red drones can be shot down for 100 points each. The crosshair turns red when one is in your line of fire.
- Speed and obstacle density increase the farther you go.
- Back button pauses.

Emulator testing: arrow keys steer, Space fires, Shift or Z raises the shield, Enter launches.

## Project layout

Plain Kotlin + Android Canvas, no game engine or extra libraries.

| File | What it does |
|---|---|
| `Game.kt` | Rules: movement, spawning obstacles, collisions, shield, weapons, scoring. Tuning constants are at the top. |
| `Renderer.kt` | Perspective projection of the trench, obstacles, ship, lasers and HUD. |
| `GameView.kt` | Game loop thread, tilt sensor reading, multi-touch zones. |
| `Entities.kt` | Obstacle, laser bolt and particle data. |

Build locally with Android Studio, or `./gradlew assembleDebug`.
