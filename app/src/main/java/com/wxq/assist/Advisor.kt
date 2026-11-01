package com.wxq.assist

/**
 * 建议引擎。
 *
 * 三条独立链路，对应三个识别按钮：
 *   阵容 → 算阵营档位缺口，匹配 64 套阵容，给补/砍建议
 *   商店 → 给每张牌排购买优先级
 *   天赋 → 三选一打分
 *
 * 跨次识别会记住当前阵容和锁定的目标阵容，所以先识别阵容再识别商店，
 * 商店建议会明显更准。
 */
object Advisor {

    /** 本局状态，一局结束可手动重置 */
    private val board = LinkedHashSet<String>()
    private var targetComp: GameData.Comp? = null

    fun resetRound() {
        board.clear()
        targetComp = null
        Logger.i("Advisor", "已重置本局状态")
    }

    fun currentBoard(): List<String> = board.toList()
    fun currentTarget(): String? = targetComp?.name

    // ================= 阵容 =================

    fun adviseBoard(ocrText: String): String {
        val found = GameData.matchNames(ocrText, GameData.Kind.HERO)
        if (found.isEmpty()) {
            return "没认出任何英雄。\n\n" + noMatchHint()
        }

        board.clear()
        found.forEach { board.add(it.name) }

        val heroes = board.mapNotNull { GameData.hero(it) }
        val campCount = heroes.groupingBy { it.camp }.eachCount()
            .filterKeys { it != "无阵营" }
            .toList().sortedByDescending { it.second }

        val sb = StringBuilder()
        sb.append("【当前阵容】${heroes.size} 名\n")
        sb.append(heroes.joinToString("、") { "${it.name}(${it.quality}阶)" })
        sb.append("\n\n")

        // ---- 阵营档位 ----
        sb.append("【阵营档位】\n")
        if (campCount.isEmpty()) {
            sb.append("  全是无阵营，拿不到联动词条\n")
        } else {
            for ((camp, n) in campCount) {
                val tiers = GameData.camp(camp)?.tiers ?: listOf(3, 5)
                val active = tiers.filter { n >= it }.maxOrNull()
                val next = tiers.firstOrNull { n < it }
                val status = when {
                    active != null && next != null -> "已激活 $active，再 ${next - n} 人上 $next"
                    active != null -> "已满档 $active"
                    next != null -> "差 ${next - n} 人激活 $next"
                    else -> "无联动"
                }
                sb.append("  $camp $n 人 — $status\n")
            }
        }
        sb.append('\n')

        // ---- 匹配阵容 ----
        val ranked = rankComps(board)
        if (ranked.isEmpty()) {
            sb.append("【推荐阵容】没有匹配项（可能都被你屏蔽了）\n")
        } else {
            sb.append("【推荐阵容】\n")
            ranked.take(3).forEachIndexed { i, (comp, score, missing) ->
                sb.append("${i + 1}. ${comp.name}  契合 ${(score * 100).toInt()}%")
                if (comp.tag.isNotEmpty()) sb.append("  [${comp.tag}]")
                sb.append('\n')
                if (comp.timing.isNotEmpty()) sb.append("   成型期：${comp.timing}\n")
                val coreHave = comp.core.filter { board.contains(it) }
                val coreNeed = comp.core.filter { !board.contains(it) }
                if (coreHave.isNotEmpty()) sb.append("   核心已有：${coreHave.joinToString("、")}\n")
                if (coreNeed.isNotEmpty()) sb.append("   ⭐核心缺：${coreNeed.joinToString("、")}\n")
                if (missing.isNotEmpty()) {
                    sb.append("   还需补：${missing.take(5).joinToString("、")}\n")
                }
            }
            targetComp = ranked.first().first

            // ---- 多余的牌 ----
            val target = ranked.first().first
            val extra = board.filter { !target.heroes.contains(it) }
            if (extra.isNotEmpty()) {
                sb.append("\n【可考虑下场】（不在「${target.name}」里）\n")
                sb.append("  ${extra.joinToString("、")}\n")
            }
        }

        appendLowConfidence(sb, found)
        appendDataWarnings(sb, ocrText, found)
        return sb.toString()
    }

    /** 返回 (阵容, 契合度, 缺的英雄) */
    private fun rankComps(have: Set<String>): List<Triple<GameData.Comp, Float, List<String>>> {
        val blocked = Prefs.blockedComps()
        return GameData.comps
            .filter { !blocked.contains(it.id) }
            .map { comp ->
                val hit = comp.heroes.count { have.contains(it) }
                val coreHit = comp.core.count { have.contains(it) }
                // 核心牌权重更高：核心对上了才算真的在走这套
                val score = if (comp.heroes.isEmpty()) 0f
                else (hit.toFloat() / comp.heroes.size) * 0.6f +
                    (if (comp.core.isEmpty()) 0f else coreHit.toFloat() / comp.core.size) * 0.4f
                Triple(comp, score, comp.heroes.filter { !have.contains(it) })
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
    }

    // ================= 商店 =================

    fun adviseShop(ocrText: String): String {
        val found = GameData.matchNames(ocrText, GameData.Kind.HERO)
        if (found.isEmpty()) {
            return "商店里没认出英雄牌。\n\n" + noMatchHint()
        }

        val sb = StringBuilder()
        sb.append("【商店】认出 ${found.size} 张\n")
        if (board.isEmpty()) {
            sb.append("⚠ 还没识别过阵容，建议先点「识别当前阵容」，商店排序会准很多。\n")
        }
        targetComp?.let { sb.append("目标阵容：${it.name}\n") }
        sb.append('\n')

        val campCount = board.mapNotNull { GameData.hero(it) }
            .groupingBy { it.camp }.eachCount()

        data class Pick(val name: String, val score: Int, val reasons: List<String>)

        val picks = found.mapNotNull { m ->
            val h = GameData.hero(m.name) ?: return@mapNotNull null
            var score = 0
            val reasons = mutableListOf<String>()

            val tc = targetComp
            if (tc != null) {
                when {
                    tc.core.contains(h.name) -> {
                        score += 50; reasons.add("目标阵容核心")
                    }
                    tc.heroes.contains(h.name) -> {
                        score += 25; reasons.add("目标阵容成员")
                    }
                }
            }

            if (board.contains(h.name)) {
                score += 30
                reasons.add("已有同名，推进觉醒（集满4张）")
            }

            if (h.camp != "无阵营") {
                val n = campCount[h.camp] ?: 0
                val tiers = GameData.camp(h.camp)?.tiers ?: listOf(3, 5)
                val next = tiers.firstOrNull { n < it }
                if (next != null && n + 1 >= next) {
                    score += 40
                    reasons.add("补上就激活 ${h.camp} $next 档")
                } else if (next != null) {
                    score += 10
                    reasons.add("${h.camp} $n→${n + 1}，离 $next 档还差 ${next - n - 1}")
                }
            }

            // 低阶牌前期打工，高阶牌后期成型
            if (h.quality >= 4) { score += 8; reasons.add("${h.quality}阶高费") }
            if (reasons.isEmpty()) reasons.add("与当前思路关联不大")

            Pick(h.name, score, reasons)
        }.sortedByDescending { it.score }

        picks.forEachIndexed { i, p ->
            val tag = when {
                p.score >= 60 -> "🔥必买"
                p.score >= 35 -> "👍可买"
                p.score >= 15 -> "➖看能量"
                else -> "❌先跳过"
            }
            sb.append("${i + 1}. ${p.name}  $tag (${p.score}分)\n")
            p.reasons.forEach { sb.append("   · $it\n") }
        }

        appendLowConfidence(sb, found)
        appendDataWarnings(sb, ocrText, found)
        return sb.toString()
    }

    // ================= 天赋 =================

    fun adviseTalent(ocrText: String): String {
        val found = GameData.matchNames(ocrText, GameData.Kind.TALENT)
        if (found.isEmpty()) {
            return "没认出天赋牌。\n\n" + noMatchHint()
        }

        val sb = StringBuilder()
        sb.append("【天赋三选一】认出 ${found.size} 张\n\n")

        val boardKw = board.mapNotNull { GameData.hero(it) }
            .flatMap { it.kw }
            .groupingBy { it }.eachCount()

        data class Pick(val t: GameData.Talent, val score: Int, val reasons: List<String>)

        val picks = found.mapNotNull { m ->
            val t = GameData.talent(m.name) ?: return@mapNotNull null
            var score = t.quality * 10   // 铜1 银2 金3
            val reasons = mutableListOf<String>()
            reasons.add(when (t.quality) { 3 -> "金色"; 2 -> "银色"; else -> "铜色" })

            // 和场上词条的联动才是真价值，不是无脑选高品级
            val hits = boardKw.keys.filter { t.desc.contains(it) }
            if (hits.isNotEmpty()) {
                score += hits.sumOf { (boardKw[it] ?: 0) * 12 }
                reasons.add("联动场上词条：${hits.joinToString("、")}")
            }

            // 点名了场上英雄
            val namedHeroes = board.filter { t.desc.contains(it) }
            if (namedHeroes.isNotEmpty()) {
                score += namedHeroes.size * 15
                reasons.add("直接点名：${namedHeroes.joinToString("、")}")
            }

            targetComp?.let { tc ->
                val tcHit = tc.core.filter { t.desc.contains(it) }
                if (tcHit.isNotEmpty()) {
                    score += 20
                    reasons.add("强化目标阵容核心：${tcHit.joinToString("、")}")
                }
            }

            if (hits.isEmpty() && namedHeroes.isEmpty()) {
                reasons.add("与当前阵容无直接联动")
            }
            Pick(t, score, reasons)
        }.sortedByDescending { it.score }

        picks.forEachIndexed { i, p ->
            sb.append("${if (i == 0) "✅ 推荐" else "${i + 1}."} ${p.t.name} (${p.score}分)\n")
            p.reasons.forEach { sb.append("   · $it\n") }
            sb.append("   ${p.t.desc.take(60)}${if (p.t.desc.length > 60) "…" else ""}\n")
        }

        if (board.isEmpty()) {
            sb.append("\n⚠ 还没识别过阵容，打分只看了品级。先识别阵容会准很多。\n")
        }
        appendLowConfidence(sb, found)
        appendDataWarnings(sb, ocrText, found)
        return sb.toString()
    }

    // ================= 工具 =================

    private fun appendLowConfidence(sb: StringBuilder, found: List<GameData.Match>) {
        val low = found.filter { it.confidence < 0.9f }
        if (low.isNotEmpty()) {
            sb.append("\n⚠ 模糊匹配（可能认错）：")
            sb.append(low.joinToString("、") { "${it.name}(${(it.confidence * 100).toInt()}%)" })
            sb.append('\n')
        }
    }

    /** 报出库里没有的卡名 + 数据过期提醒 */
    private fun appendDataWarnings(
        sb: StringBuilder, ocrText: String, found: List<GameData.Match>
    ) {
        val unknown = GameData.unknownSegments(ocrText, found)
        if (unknown.isNotEmpty()) {
            sb.append("\n❓ 认出但数据库里没有：")
            sb.append(unknown.joinToString("、"))
            sb.append("\n   （可能是建木阵营等新内容，见 README 的 custom.json 说明）\n")
        }
        val age = GameData.dataAgeDays()
        if (age > 21) {
            sb.append("\n⏰ 内置数据已 $age 天未更新，游戏是周更，建议补数据。\n")
        }
    }

    private fun noMatchHint(): String = buildString {
        append("可能的原因：\n")
        append("· 区域框没对准 — 到「识别」页做一次区域校准\n")
        append("· 当前画面不是这个页面\n")
        append("· 卡面字太小 — 试试把区域框收紧，只框住卡名那一条\n")
        append("原始 OCR 文本见「日志」页。")
    }
}
