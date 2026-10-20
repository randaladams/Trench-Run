package com.randaladams.trenchrun

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One finished run. */
data class RunRecord(val score: Int, val distance: Int, val kills: Int, val time: Long) {
    fun toJson(): JSONObject = JSONObject().put("s", score).put("d", distance).put("k", kills).put("t", time)

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(o.optInt("s"), o.optInt("d"), o.optInt("k"), o.optLong("t"))
    }
}

/** [pro] badges can only be earned in the Pro edition. */
class Badge(val id: String, val name: String, val desc: String, val color: Int, val pro: Boolean = false)

/**
 * Persistent scoreboard and badges.
 * Keeps the all-time best run (shown as #1) plus the 19 most recent runs.
 */
class ScoreBook(private val prefs: SharedPreferences) {

    companion object {
        const val RECENT = 19

        val BADGES = listOf(
            // ---- free edition
            Badge("first", "First Flight", "Finish your first run", 0xFF9FF5C8.toInt()),
            Badge("grad", "Graduate", "Complete the tutorial", 0xFFFFD27A.toInt()),
            Badge("km1", "Trench Rookie", "Fly 1,000 m in one run", 0xFF8FB8FF.toInt()),
            Badge("km3", "Trench Veteran", "Fly 3,000 m in one run", 0xFF6F9BFF.toInt()),
            Badge("sharp", "Sharpshooter", "Destroy 25 drones in one run", 0xFFFF7050.toInt()),
            Badge("deflector", "Deflector", "Shoot down 5 turret lasers in total", 0xFFFF9A6A.toInt()),
            Badge("armed", "Fully Armed", "Reach Laser Mk IV", 0xFF7CFF4A.toInt()),
            Badge("untouch", "Untouchable", "Fly 1,500 m without a crash", 0xFF40E0FF.toInt()),
            Badge("dedicated", "Dedicated", "Play 25 runs", 0xFFFFE080.toInt()),
            // ---- pro edition
            Badge("km6", "Trench Legend", "Fly 6,000 m in one run", 0xFFB48CFF.toInt(), true),
            Badge("score10k", "High Roller", "Score 10,000 in one run", 0xFFFFC040.toInt(), true),
            Badge("exterm", "Exterminator", "Destroy 250 drones in total", 0xFFFF4A4A.toInt(), true),
            Badge("laserwall", "Laser Wall", "Shoot down 50 turret lasers in total", 0xFFFF6A3A.toInt(), true),
            Badge("collector", "Collector", "Catch 5 orbs with the tractor beam in one run", 0xFF7FB8FF.toInt(), true),
            Badge("cool", "Cool Head", "Destroy 15 drones in a run without overheating", 0xFF5CFF7A.toInt(), true),
            Badge("bender", "Time Bender", "Collect 3 slow-mo orbs in one run", 0xFFC08CFF.toInt(), true),
            Badge("dodger", "Dodger", "Dodge 5 turret shots in one run", 0xFFFFA040.toInt(), true),
            Badge("survivor", "Survivor", "Recover to 50 shield after it hit zero", 0xFFE0E6EE.toInt(), true),
            Badge("purist", "Purist", "Fly 2,000 m without using the tractor beam", 0xFFA8C8E8.toInt(), true),
            Badge("zen", "Zen Master", "Fly 5,000 m in one Zen run", 0xFF9FE8D0.toInt(), true)
        )
    }

    /** Edition switch. Will be driven by a Play Store purchase later; for now a hidden test toggle. */
    var pro = prefs.getBoolean("pro", false)
        set(v) { field = v; prefs.edit().putBoolean("pro", v).apply() }

    var laserKills = 0
        private set
    var zenBest = 0
        private set

    val recent = ArrayList<RunRecord>()   // newest first
    var best: RunRecord? = null
        private set
    val earned = HashSet<String>()
    var totalKills = 0
        private set
    var runs = 0
        private set

    init { load() }

    val highScore get() = best?.score ?: 0

    private fun load() {
        recent.clear(); earned.clear()
        try {
            val arr = JSONArray(prefs.getString("recentRuns", "[]"))
            for (i in 0 until arr.length()) recent.add(RunRecord.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {}
        best = try {
            prefs.getString("bestRun", null)?.let { RunRecord.fromJson(JSONObject(it)) }
        } catch (_: Exception) { null }
        // carry over the high score saved by earlier builds
        val legacy = prefs.getInt("high", 0)
        if (best == null && legacy > 0) best = RunRecord(legacy, 0, 0, 0L)
        earned.addAll(prefs.getStringSet("badges", emptySet()) ?: emptySet())
        totalKills = prefs.getInt("totalKills", 0)
        runs = prefs.getInt("runs", 0)
        laserKills = prefs.getInt("laserKills", 0)
        zenBest = prefs.getInt("zenBest", 0)
    }

    private fun save() {
        val arr = JSONArray()
        for (r in recent) arr.put(r.toJson())
        prefs.edit()
            .putString("recentRuns", arr.toString())
            .putString("bestRun", best?.toJson()?.toString())
            .putInt("high", highScore)
            .putStringSet("badges", HashSet(earned))
            .putInt("totalKills", totalKills)
            .putInt("runs", runs)
            .putInt("laserKills", laserKills)
            .putInt("zenBest", zenBest)
            .apply()
    }

    /** Records a finished run. Returns true if it set a new high score. */
    fun record(run: RunRecord, laserKillsThisRun: Int): Boolean {
        laserKills += laserKillsThisRun
        recent.add(0, run)
        while (recent.size > RECENT) recent.removeAt(recent.size - 1)
        runs++
        totalKills += run.kills
        val isBest = run.score > highScore
        if (isBest) best = run
        save()
        return isBest
    }

    /** Awards a badge. Returns the badge if it was newly earned, otherwise null. */
    fun award(id: String): Badge? {
        if (earned.contains(id)) return null
        val b = BADGES.firstOrNull { it.id == id } ?: return null
        if (b.pro && !pro) return null
        earned.add(id)
        save()
        return b
    }

    fun recordZen(distance: Int) {
        if (distance > zenBest) { zenBest = distance; save() }
    }

    /** Clears scores and badges. The edition (free/pro) is kept. */
    fun clearAll() {
        recent.clear(); earned.clear(); best = null
        totalKills = 0; runs = 0; laserKills = 0; zenBest = 0
        prefs.edit().remove("high").apply()
        save()
    }
}
