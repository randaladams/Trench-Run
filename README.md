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
- **Shield energy** is your lifeline. A hit costs 50 energy, or 20 if your shield is raised. Raising it drains energy while held. Energy rebuilds very slowly (0.3/s) and +2 for every drone you shoot down.
- **If you hit something with no shield energy left, the run is over.**
- **Cyan orbs** restore 25 shield energy. **Green orbs** upgrade your lasers (Mk I to IV: twin shots, faster fire, more damage, triple shot). **Violet hourglass orbs** slow the trench to 45% speed for 12 seconds and also permanently knock the speed ramp back a notch. Orbs appear on timers (shield about every 18 s, slow-mo about every 22 s, lasers about every 30 s), so flying faster doesn't earn you more of them.
- **Wall turrets** appear now and then. Their muzzle glows and whines as you approach, then fires one red laser at where you are. Move to dodge it, or raise your shield (30 damage unshielded, 10 shielded). You can hear which wall it's on.
- When shield energy drops below 25 you hear one warning beep and a spoken "Shield critical" (using the phone's text-to-speech voice), and the screen edge pulses red. At zero you hear "Shields down!"
- Red drones can be shot down for 100 points each. The crosshair turns red when one is in your line of fire.
- Speed and obstacle density increase the farther you go, and speed keeps creeping up slowly after that to a capped top speed. Current speed is shown top right.
- Pause and sound on/off buttons sit at the bottom center of the screen. The back button also pauses, and the phone volume buttons adjust sound.

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
