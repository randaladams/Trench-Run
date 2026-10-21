# Trench Run

A tilt-controlled Android arcade game (version 1.0.0): fly a fighter down a long trench, dodging bridges, raised floors and walls while blasting drones.

## Download (always the newest build)

**https://github.com/randaladams/Trench-Run/releases/latest/download/TrenchRun.apk**

Open that link on your Android phone, then open the downloaded file to install. Allow installs from your browser if Android asks. This link never changes: every push to `main` rebuilds the game (see the **Actions** tab, about 2 minutes) and replaces the file behind it. New builds install over the old one, and the version and build number show in the corner of the title screen.

## How to play

| Control | Action |
|---|---|
| Tilt left / right | Steer left / right |
| Tilt forward / back | Dive / climb (swap on the title screen) |
| Hold the **left** side of the screen | Fire lasers at the crosshair |
| Hold the **right** side of the screen | Tractor beam: pulls nearby orbs to you (drains shield energy) |

New to the game? Tap **TUTORIAL** on the title screen for a guided training run where you can't die.

**Home screen:** a big **START** button launches a run. Below it: Tutorial, Zen mode on/off (Pro), Scores & Badges, pitch direction, sound, and **Exit game**. After a crash, choose **Fly again** or **Main menu**.

- Hold the phone however is comfortable, then press START. That angle becomes "level".
- On the home screen the ship already follows your tilt, so you can fly it around while you pick an option; "level" there slowly adjusts to how you're holding the phone.
- **Color code: red and striped = danger. Glowing cyan and green orbs = good.**
- **Shield energy** is your lifeline and works automatically. Each crash costs 50 energy. Energy rebuilds very slowly (0.3/s, paused while the tractor beam is on) and +2 for every drone you shoot down.
- The **tractor beam** reaches about 75 m ahead and drags any orbs in range toward your ship, at 9 energy per second. Use it in short bursts to grab orbs you'd otherwise miss.
- **If you hit something with no shield energy left, the run is over**, but once per run you're offered a **Continue**: an 8-second countdown lets you jump back in where you crashed with your score kept and 50 shield. Free edition: watch a short ad to continue. Pro: continue with no ad. The run is saved to the scoreboard only after you decline or the countdown runs out.
- **Cyan orbs** restore 25 shield energy. **Green orbs** upgrade your lasers (Mk I to IV: twin shots, faster fire, more damage, triple shot). **Violet hourglass orbs** slow the trench to 45% speed for 12 seconds and also permanently knock the speed ramp back a notch. Orbs appear on timers (shield about every 14 s, slow-mo about every 22 s, lasers about every 30 s), so flying faster doesn't earn you more of them.
- **Wall turrets** appear now and then. Their muzzle glows and whines as you approach, then fires one red laser at where you are. Dodge it or shoot it down (+150 points). It does 30 damage if it hits. You can hear which wall it's on.
- Firing costs shield energy: about 3 per second of continuous fire.
- **Hull scraping:** fly too low and the ship drags along the floor; fly too wide and a wingtip grinds along a wall. Either one throws sparkler-style spark streaks with a grinding sound (panned to the side you're touching) and drains a little shield energy (up to 5/s on the floor, 4/s on a wall). It never slows you down.
- When shield energy drops below 25 you hear one warning beep and a spoken "Shield critical" (using the phone's text-to-speech voice), and the screen edge pulses red. At zero you hear "Shields down!"
- **Laser heat:** firing builds heat (shown on the HEAT gauge under the shield bar). Holding fire fills it in about 4 seconds; then the lasers overheat and lock for about 3 seconds until they cool. Fire in bursts to stay below the limit.
- Red drones can be shot down for 100 points each. The crosshair turns red when one is in your line of fire.
- Speed and obstacle density increase the farther you go, and speed keeps creeping up slowly after that to a capped top speed. Current speed is shown top right.
- Pause and sound on/off buttons sit at the bottom center of the screen. The pause screen has **Resume** and **End game**. The back button also pauses, and the phone volume buttons adjust sound.

## Scoreboard and badges

Tap **SCORES & BADGES** on the title screen. Two tabs at the top switch between the **Scoreboard** and **Badges** screens. On the scoreboard, #1 is always your all-time high score and #2-20 are your 19 most recent runs, newest first, in two columns of ten, with lifetime totals underneath. There are 24 badges: 10 in the free edition and 14 more in Pro (marked with a PRO tag). A banner pops up when you earn one. **CLEAR ALL** erases scores and badges after a confirmation. The app version is shown in the top-right corner.

## Free and Pro editions

The game has a free edition and a Pro edition (planned as the paid Play Store version):

| | Free | Pro |
|---|---|---|
| Main game, tutorial, scoreboard | Yes | Yes |
| Badges | 10 | All 24 |
| Zen mode (steady speed, no turrets, crashes don't hurt, lasers never overheat) | No | Yes |
| One continue per run | After watching an ad | Free, no ad |
| Ads | Banner on menu screens, full-screen ad between some runs | None |

**Going Pro:** free players see a gold **GO PRO** button in the top-left of the home screen (also opened by tapping the locked Zen mode button or the PRO note on the Badges screen). It shows what Pro includes with **Unlock Pro**, **Restore purchase** and **Not now**. Google Play Billing isn't connected yet: in test builds Unlock Pro simply switches to Pro at no charge; in a store build it says purchases are coming.

**Test switch:** open Scores & Badges and **press and hold the SCOREBOARD tab for 2 seconds** (you'll see "Keep holding..." and then feel a buzz). Tapping the **FREE EDITION** / **PRO EDITION** label on the home screen 5 times quickly also works. It only works in debug builds (like the ones on the download link), so it won't exist in a store release. Before publishing, the Pro flag should be driven by a Google Play purchase instead.

## Ads (AdMob)

Free edition only:
- A banner at the bottom of the home, Scores & Badges and game-over screens (on Scores & Badges it sits between the BACK and CLEAR ALL buttons). Never during a run.
- A full-screen ad before a new run, at most every 3 runs and at least 2 minutes apart, and never in a player's first 3 runs. Tuning is at the top of `Ads.kt`.
- A privacy consent prompt appears where the law requires it (Google's UMP SDK).

Test builds (the download link) always use Google's public **test** ads, so tapping them is safe. The Play Store (release) build uses the real ids set at the top of `app/build.gradle.kts` (`REAL_APP_ID`, `REAL_BANNER_ID`, `REAL_INTERSTITIAL_ID`, `REAL_REWARDED_ID`) and falls back to test ids while those are empty. Before publishing, also add an `app-ads.txt` file to the developer website listed on the Play Store, and fill in the ads and Data safety sections in the Play Console.

Package name: `com.adamselite.trenchrun`.

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
| `Ads.kt` | AdMob banner and between-run ads, consent prompt, frequency limits. |
| `Sound.kt` | Synthesized sound effects and engine/shield hum loops. |

Build locally with Android Studio, or `./gradlew assembleDebug`.

`store/` holds the Play Store assets: `play-icon-512.png` (the 512 x 512 store icon) and `icon-source.svg` (the source the launcher icons are rendered from).
