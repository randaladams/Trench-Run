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
| Hold the **right** side of the screen | Tractor beam: pulls nearby orbs to you (drains shield energy) |

New to the game? Tap **TUTORIAL** on the title screen for a guided training run where you can't die.

- Hold the phone however is comfortable, then tap to launch. That angle becomes "level".
- **Color code: red and striped = danger. Glowing cyan and green orbs = good.**
- **Shield energy** is your lifeline and works automatically. Each crash costs 50 energy. Energy rebuilds very slowly (0.3/s, paused while the tractor beam is on) and +2 for every drone you shoot down.
- The **tractor beam** reaches about 75 m ahead and drags any orbs in range toward your ship, at 9 energy per second. Use it in short bursts to grab orbs you'd otherwise miss.
- **If you hit something with no shield energy left, the run is over.**
- **Cyan orbs** restore 25 shield energy. **Green orbs** upgrade your lasers (Mk I to IV: twin shots, faster fire, more damage, triple shot). **Violet hourglass orbs** slow the trench to 45% speed for 12 seconds and also permanently knock the speed ramp back a notch. Orbs appear on timers (shield about every 14 s, slow-mo about every 22 s, lasers about every 30 s), so flying faster doesn't earn you more of them.
- **Wall turrets** appear now and then. Their muzzle glows and whines as you approach, then fires one red laser at where you are. Dodge it or shoot it down (+150 points). It does 30 damage if it hits. You can hear which wall it's on.
- Firing costs a tiny bit of shield energy (about 0.8 per second of continuous fire).
- When shield energy drops below 25 you hear one warning beep and a spoken "Shield critical" (using the phone's text-to-speech voice), and the screen edge pulses red. At zero you hear "Shields down!"
- **Laser heat:** firing builds heat (shown on the HEAT gauge under the shield bar). Holding fire fills it in about 4 seconds; then the lasers overheat and lock for about 3 seconds until they cool. Fire in bursts to stay below the limit.
- Red drones can be shot down for 100 points each. The crosshair turns red when one is in your line of fire.
- Speed and obstacle density increase the farther you go, and speed keeps creeping up slowly after that to a capped top speed. Current speed is shown top right.
- Pause and sound on/off buttons sit at the bottom center of the screen. The pause screen has **Resume** and **End game**. The back button also pauses, and the phone volume buttons adjust sound.

## Scoreboard and badges

Tap **SCORES & BADGES** on the title screen. Row 1 is always your all-time high score; rows 2-20 are your 19 most recent runs, newest first. There are 20 badges: 9 in the free edition and 11 more in Pro (marked with a PRO tag). A banner pops up when you earn one. **CLEAR ALL** erases scores and badges after a confirmation. The app version is shown at the bottom right.

## Free and Pro editions

The game has a free edition and a Pro edition (planned as the paid Play Store version):

| | Free | Pro |
|---|---|---|
| Main game, tutorial, scoreboard | Yes | Yes |
| Badges | 9 | All 20 |
| Zen mode (steady speed, no turrets, crashes don't hurt) | No | Yes |

**Test switch:** on the scoreboard, tap the version line in the bottom-right corner 7 times quickly to flip between Free and Pro. It only works in debug builds (like the ones on the download link), so it won't exist in a store release. Before publishing, the Pro flag should be driven by a Google Play purchase instead.

Emulator testing: arrow keys steer, Space fires, Shift or Z holds the tractor beam, Enter launches.

## Project layout

Plain Kotlin + Android Canvas, no game engine or extra libraries.

| File | What it does |
|---|---|
| `Game.kt` | Rules: movement, spawning obstacles, collisions, shield, weapons, scoring. Tuning constants are at the top. |
| `Renderer.kt` | Perspective projection of the trench, obstacles, ship, lasers and HUD. |
| `GameView.kt` | Game loop thread, tilt sensor reading, multi-touch zones. |
| `Entities.kt` | Obstacle, laser bolt and particle data. |
| `Textures.kt` | Procedurally painted wall and floor textures (plating, pipes, vents, lights). |
| `ScoreBook.kt` | Saved scoreboard (last 19 runs + best) and badge definitions. |
| `Sound.kt` | Synthesized sound effects and engine/shield hum loops. |

Build locally with Android Studio, or `./gradlew assembleDebug`.
