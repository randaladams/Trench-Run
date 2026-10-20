package com.randaladams.trenchrun

/**
 * World coordinates (units are arbitrary "meters"):
 *   x: left/right across the trench, 0 = center, walls at +/- Game.HALF_W
 *   y: up, floor at 0, top of the walls at Game.TOP
 *   z: forward, the player's ship always sits at z = 0 and the world scrolls toward it.
 */
enum class Kind {
    BRIDGE,        // horizontal beam spanning the trench: fly over or under
    RAISED_FLOOR,  // floor section that rises up: fly over
    CEILING,       // block hanging down from the top: fly under
    SIDE_BLOCK,    // wall jutting in from one side: dodge sideways
    PILLAR,        // column standing in the trench: dodge left or right
    DRONE,         // floating target: shoot it or dodge it
    PICKUP_SHIELD, // fly through for shield energy
    PICKUP_WEAPON  // fly through for a laser upgrade
}

class Entity(
    val kind: Kind,
    var x0: Float, var x1: Float,
    var y0: Float, var y1: Float,
    var z0: Float, var z1: Float
) {
    var alive = true
    var spent = false        // already hit the player once
    var hp = 1
    var hitFlash = 0f
    var phase = 0f
    var baseX = 0f
    var driftAmp = 0f

    val isPickup get() = kind == Kind.PICKUP_SHIELD || kind == Kind.PICKUP_WEAPON
    val destructible get() = kind == Kind.DRONE
    val cx get() = (x0 + x1) * 0.5f
    val cy get() = (y0 + y1) * 0.5f
}

class Bolt(
    var x: Float, var y: Float, var z: Float,
    val vx: Float, val vy: Float, val vz: Float,
    val damage: Int
) {
    var alive = true
    var prevZ = z
}

class Particle(
    var x: Float, var y: Float, var z: Float,
    val vx: Float, val vy: Float, val vz: Float,
    val maxLife: Float, val color: Int, val size: Float
) {
    var life = maxLife
}
