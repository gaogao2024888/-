package com.wxq.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 游戏数据库。
 *
 * 优先读外部文件（/Android/data/<包名>/files/data/），没有才用内置 assets。
 * 这样游戏更新后你直接换 JSON 就行，不用重新编译 APK。
 */
object GameData {

    data class Hero(
        val id: Int,
        val name: String,
        val quality: Int,      // 阶数
        val camp: String,      // 阵营
        val role: String,      // 前排/后排
        val kw: List<String>   // 词条
    )

    data class Camp(
        val name: String,
        val total: Int,
        val tiers: List<Int>,  // 联动档位，通常 3/5；无阵营为空
        val members: List<String>
    )

    data class Comp(
        val id: String,
        val name: String,
        val camps: List<String>,
        val core: List<String>,
        val heroes: List<String>,
        val timing: String,
        val tag: String
    )

    data class Talent(
        val id: Int,
        val name: String,
        val quality: Int,
        val stage: String,
        val group: String,
        val desc: String
    )

    data class Effect(
        val id: Int, val name: String, val quality: Int,
        val cat: String, val desc: String
    )

    var heroes: List<Hero> = emptyList(); private set
    var camps: List<Camp> = emptyList(); private set
    var comps: List<Comp> = emptyList(); private set
    var talents: List<Talent> = emptyList(); private set
    var effects: List<Effect> = emptyList(); private set

    var generatedAt: String = ""; private set
    var loadedFrom: String = ""; private set

    private val heroByName = HashMap<String, Hero>()
    private val talentByName = HashMap<String, Talent>()
    private val effectByName = HashMap<String, Effect>()
    private val campByName = HashMap<String, Camp>()

    /** OCR 匹配用：所有已知中文名，按长度降序，先长后短避免「李信」吃掉「李信·光」 */
    private var allHeroNames: List<String> = emptyList()
    private var allTalentNames: List<String> = emptyList()
    private var allEffectNames: List<String> = emptyList()

    @Volatile
    var loaded = false
        private set

    fun externalDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "data")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val t0 = System.currentTimeMillis()
        try {
            val ext = externalDir(ctx)
            val useExt = File(ext, "heroes.json").exists()
            loadedFrom = if (useExt) "外部文件" else "内置"

            fun read(name: String): String =
                if (useExt && File(ext, "$name.json").exists())
                    File(ext, "$name.json").readText()
                else
                    ctx.assets.open("data/$name.json").bufferedReader().use { it.readText() }

            heroes = JSONArray(read("heroes")).mapObj {
                Hero(
                    it.getInt("id"), it.getString("n"), it.optInt("q"),
                    it.optString("c"), it.optString("r"),
                    it.optJSONArray("kw")?.toStringList() ?: emptyList()
                )
            }

            camps = JSONArray(read("camps")).mapObj {
                Camp(
                    it.getString("n"), it.optInt("total"),
                    it.optJSONArray("tiers")?.toIntList() ?: emptyList(),
                    it.optJSONArray("members")?.toStringList() ?: emptyList()
                )
            }

            comps = JSONArray(read("comps")).mapObj {
                Comp(
                    it.getString("id"), it.getString("n"),
                    it.optJSONArray("camps")?.toStringList() ?: emptyList(),
                    it.optJSONArray("core")?.toStringList() ?: emptyList(),
                    it.optJSONArray("hs")?.toStringList() ?: emptyList(),
                    it.optString("tim"), it.optString("tag")
                )
            }

            talents = JSONArray(read("talents")).mapObj {
                Talent(
                    it.getInt("id"), it.getString("n"), it.optInt("q"),
                    it.optString("st"), it.optString("g"), it.optString("d")
                )
            }

            effects = JSONArray(read("effects")).mapObj {
                Effect(
                    it.getInt("id"), it.getString("n"), it.optInt("q"),
                    it.optString("cat"), it.optString("d")
                )
            }

            generatedAt = runCatching {
                JSONObject(read("meta")).optString("generatedAt")
            }.getOrDefault("")

            // 补丁层：外部 custom.json 可以追加官方数据里还没有的新卡
            // 游戏周更，内置数据必然落后，这层让你不用等我重新打包
            runCatching {
                val cf = File(ext, "custom.json")
                if (cf.exists()) {
                    val o = JSONObject(cf.readText())
                    val addHeroes = o.optJSONArray("heroes")
                    if (addHeroes != null) {
                        val extra = addHeroes.mapObj {
                            Hero(
                                it.optInt("id", -1), it.getString("n"), it.optInt("q"),
                                it.optString("c"), it.optString("r"),
                                it.optJSONArray("kw")?.toStringList() ?: emptyList()
                            )
                        }
                        heroes = heroes + extra.filter { e -> heroes.none { it.name == e.name } }
                        Logger.i("GameData", "custom.json 追加 ${extra.size} 名英雄")
                    }
                    val addCamps = o.optJSONArray("camps")
                    if (addCamps != null) {
                        val extra = addCamps.mapObj {
                            Camp(
                                it.getString("n"), it.optInt("total"),
                                it.optJSONArray("tiers")?.toIntList() ?: listOf(3, 5),
                                it.optJSONArray("members")?.toStringList() ?: emptyList()
                            )
                        }
                        camps = camps + extra.filter { e -> camps.none { it.name == e.name } }
                        Logger.i("GameData", "custom.json 追加 ${extra.size} 个阵营")
                    }
                }
            }.onFailure { Logger.w("GameData", "custom.json 解析失败: ${it.message}") }

            heroByName.clear(); heroes.forEach { heroByName[it.name] = it }
            talentByName.clear(); talents.forEach { talentByName[it.name] = it }
            effectByName.clear(); effects.forEach { effectByName[it.name] = it }
            campByName.clear(); camps.forEach { campByName[it.name] = it }

            allHeroNames = heroes.map { it.name }.sortedByDescending { it.length }
            allTalentNames = talents.map { it.name }.sortedByDescending { it.length }
            allEffectNames = effects.map { it.name }.sortedByDescending { it.length }

            loaded = true
            Logger.i(
                "GameData",
                "数据加载完成($loadedFrom, ${System.currentTimeMillis() - t0}ms): " +
                    "${heroes.size}英雄 ${camps.size}阵营 ${comps.size}阵容 " +
                    "${talents.size}天赋 ${effects.size}效果牌，数据日期 $generatedAt"
            )
        } catch (t: Throwable) {
            Logger.e("GameData", "数据加载失败", t)
        }
    }

    fun hero(name: String): Hero? = heroByName[name]
    fun camp(name: String): Camp? = campByName[name]
    fun talent(name: String): Talent? = talentByName[name]
    fun effect(name: String): Effect? = effectByName[name]

    // ---------------- OCR 文本匹配 ----------------

    enum class Kind { HERO, TALENT, EFFECT }

    data class Match(val name: String, val kind: Kind, val confidence: Float)

    /**
     * 从一段 OCR 文本里找出所有已知卡名。
     * OCR 对游戏字体经常多字少字，所以三级匹配：
     *   1. 直接包含        → 1.00
     *   2. 去噪后包含      → 0.90
     *   3. 编辑距离 ≤1     → 0.70（只对 ≥3 字的名字，避免二字名乱撞）
     */
    fun matchNames(raw: String, kind: Kind): List<Match> {
        val pool = when (kind) {
            Kind.HERO -> allHeroNames
            Kind.TALENT -> allTalentNames
            Kind.EFFECT -> allEffectNames
        }
        if (pool.isEmpty()) return emptyList()

        val text = raw.replace(Regex("\\s+"), "")
        val clean = text.filter { it.isCJK() || it.isDigit() || it.isLetter() }
        val found = LinkedHashMap<String, Match>()

        for (n in pool) {
            if (found.containsKey(n)) continue
            when {
                text.contains(n) -> found[n] = Match(n, kind, 1.0f)
                clean.contains(n) -> found[n] = Match(n, kind, 0.90f)
                n.length >= 3 && fuzzyContains(clean, n) -> found[n] = Match(n, kind, 0.70f)
            }
        }
        return found.values.sortedByDescending { it.confidence }
    }

    /** 在 text 的滑动窗口里找与 name 编辑距离 ≤1 的片段 */
    private fun fuzzyContains(text: String, name: String): Boolean {
        val n = name.length
        if (text.length < n - 1) return false
        for (w in intArrayOf(n, n - 1, n + 1)) {
            if (w <= 0 || w > text.length) continue
            for (i in 0..(text.length - w)) {
                if (editDistance(text.substring(i, i + w), name) <= 1) return true
            }
        }
        return false
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun Char.isCJK(): Boolean = this.code in 0x4E00..0x9FFF

    /**
     * 找出 OCR 文本里"像卡名但库里没有"的片段。
     *
     * 游戏是周更，建木阵营和新英雄随时会出现在你的屏幕上但不在这份数据里。
     * 与其静默丢掉，不如报出来——你看到了就能加进 custom.json。
     */
    fun unknownSegments(raw: String, matched: List<Match>): List<String> {
        val known = matched.map { it.name }
        var text = raw
        known.forEach { text = text.replace(it, "｜") }

        return text.split(Regex("[^\\u4e00-\\u9fff]+"))
            .map { it.trim() }
            .filter { it.length in 2..6 }
            .filter { seg -> GameData.heroes.none { seg.contains(it.name) } }
            .distinct()
            .take(8)
    }

    /** 数据距今多少天，用来提醒你该更新了 */
    fun dataAgeDays(): Int {
        if (generatedAt.isEmpty()) return -1
        return runCatching {
            val p = generatedAt.split("-").map { it.toInt() }
            val cal = java.util.Calendar.getInstance()
            cal.set(p[0], p[1] - 1, p[2], 0, 0, 0)
            ((System.currentTimeMillis() - cal.timeInMillis) / 86_400_000L).toInt()
        }.getOrDefault(-1)
    }

    // ---------------- JSON 小工具 ----------------

    private inline fun <T> JSONArray.mapObj(f: (JSONObject) -> T): List<T> =
        (0 until length()).map { f(getJSONObject(it)) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { getInt(it) }
}
