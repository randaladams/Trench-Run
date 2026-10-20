# Trench Run

A tilt-controlled Android arcade prototype: fly a fighter down a long trench, dodging bridges, raised floors and walls while blasting drones.

## Download (always the newest build)

**https://github.com/randaladams/Trench-Run/releases/latest/download/TrenchRun.apk**

Open that link on your Android phone, then open the downloaded file to install. Allow installs from your browser if Android asks. This link never changes: every push to `main` rebuilds the game (see the **Actions** tab, about 2 minutes) and replaces the file behind it. New builds install over the old one, and the build number shows in the corner of the title screen.

## How to play

| Control | Action |
|---|---|
| Tilt left / right | Steer left / right |
| Tilt forward / back | Dive / climb (swap on the title screen) |
| Hold the **left** side of the screen | Fire lasers at the crosshair |
| Hold the **right** side of the screen | Raise the shield |

- Hold the phone however is comfortable, then tap to launch. That angle becomes "level".
- **Color code: red and striped = danger. Glowing cyan and green orbs = good.**
- **Shield energy** is your lifeline. A hit costs 40 energy, or 15 if your shield is raised. Raising it drains energy while held. Energy rebuilds slowly as you fly (0.5/s, growing a little with distance) and +4 for every drone you shoot down.
- **If you hit something with no shield energy left, the run is over.**
- **Cyan orbs** restore 25 shield energy. **Green orbs** upgrade your lasers (Mk I to IV: twin shots, faster fire, more damage, triple shot).
- Red drones can be shot down for 100 points each. The crosshair turns red when one is in your line of fire.
- Speed and obstacle density increase the farther you go.
- Back button pauses. Sound can be turned off on the title screen; the phone volume buttons adjust it.

Emulator testing: arrow keys steer, Space fires, Shift or Z raises the shield, Enter launches.

## Project layout

Plain Kotlin + Android Canvas, no game engine or extra libraries.

| File | What it does |
|---|---|
| `Game.kt` | Rules: movement, spawning obstacles, collisions, shield, weapons, scoring. Tuning constants are at the top. |
| `Renderer.kt` | Perspective projection of the trench, obstacles, ship, lasers and HUD. |
| `GameView.kt` | Game loop thread, tilt sensor reading, multi-touch zones. |
| `Entities.kt` | Obstacle, laser bolt and particle data. |
| `Textures.kt` | Procedurally painted wall and floor textures (plating, pipes, vents, lights). |
| `Sound.kt` | Synthesized sound effects and engine/shield hum loops. |

Build locally with Android Studio, or `./gradlew assembleDebug`.
