package moe.lyniko.glyphhacker.glyph

import kotlin.math.max

object GlyphDictionary {

    val definitions: List<GlyphDefinition> by lazy {
        RAW.trimIndent()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 2) {
                    null
                } else {
                    val code = parts[0].trim()
                    val aliases = parts[1]
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    if (aliases.isEmpty()) {
                        null
                    } else {
                        val strokePlan = if (aliases.any { it.equals(IMPERFECT_ALIAS, ignoreCase = true) }) {
                            IMPERFECT_STROKE_PLAN
                        } else {
                            null
                        }
                        GlyphDefinition(
                            canonicalName = aliases.first(),
                            aliases = aliases,
                            edges = parseGlyphCode(code),
                            strokePlan = strokePlan,
                        )
                    }
                }
            }
            .toList()
    }

    private val aliasIndex: Map<String, GlyphDefinition> by lazy {
        buildMap {
            definitions.forEach { definition ->
                definition.aliases.forEach { alias ->
                    put(alias.lowercase(), definition)
                }
            }
        }
    }

    val allKnownEdges: Set<GlyphEdge> by lazy {
        buildSet {
            definitions.forEach { addAll(it.edges) }
        }
    }

    fun findByName(name: String): GlyphDefinition? {
        return aliasIndex[name.lowercase()]
    }

    fun findBestMatch(activeEdges: Set<GlyphEdge>, minimumScore: Float): GlyphMatch? {
        if (activeEdges.isEmpty()) {
            return null
        }
        var bestDefinition: GlyphDefinition? = null
        var bestScore = 0f
        definitions.forEach { definition ->
            val score = f1Score(activeEdges, definition.edges)
            if (score > bestScore) {
                bestScore = score
                bestDefinition = definition
            }
        }
        val candidate = bestDefinition ?: return null
        if (bestScore < minimumScore) {
            return null
        }
        return GlyphMatch(candidate, bestScore)
    }

    private fun f1Score(active: Set<GlyphEdge>, target: Set<GlyphEdge>): Float {
        if (active.isEmpty() || target.isEmpty()) {
            return 0f
        }
        val intersection = active.count { target.contains(it) }
        if (intersection == 0) {
            return 0f
        }
        val precision = intersection / active.size.toFloat()
        val recall = intersection / target.size.toFloat()
        return (2f * precision * recall) / max(precision + recall, 1e-6f)
    }

    private fun parseGlyphCode(code: String): Set<GlyphEdge> {
        val edges = LinkedHashSet<GlyphEdge>()
        var index = 0
        while (index + 1 < code.length) {
            val a = code[index].digitToInt(11)
            val b = code[index + 1].digitToInt(11)
            if (a != b) {
                edges += GlyphEdge.of(a, b)
            }
            index += 2
        }
        return edges
    }

    private const val IMPERFECT_ALIAS = "Imperfect"
    private val IMPERFECT_STROKE_PLAN = listOf(8, 6, 10, 8, 9, 10)

    private const val RAW = """
        89|1
        696a787a|3
        1634486a8a|Abandon
        587a8a|Adapt
        0949|Advance
        1216276a7a|After
        49676a898a|Again,Repeat
        010512233445|All
        67697a|Answer
        06092649|Attack,War
        05061617|Avoid
        0a277a|Barrier,Obstacle
        4548598a9a|Before
        083738|Begin
        3738676989|Being,Human
        696a9a|Body,Shell
        16596a9a|Breathe,Live
        099a|Call
        1734487a8a|Capture
        373a8a|Change,Modify
        01051638456a8a|Chaos,Disorder
        0a3a|Clear
        01050a1223343a45|Close All,Clear All
        698a9a|Complex
        2649677889|Conflict
        27597889|Consequence
        011223386a899a|Contemplate
        497889|Courage
        16486a8a|Create,Creation
        1216274548597a9a|Creativity,Idea,Thought
        393a9a|Creativity
        093a9a|Danger
        06386a8a|Data,Signal
        17373858|Defend
        38676a789a|Destiny
        1223|Destination
        27597a9a|Destroy,Destruction
        488a9a|Deteriorate,Erode
        386a8a|Easy
        27487a8a|Die,Death
        16677a8a|Difficult
        122334|Discover
        0545|Distance,Outside
        010a17373a|End,Close
        01091223696a9a|Enlightened,Enlightenment
        676989|Equal
        01166989|Escape
        0a899a|Evolution,Progress,Success
        0a676a|Failure
        176769|Fear
        1517373858|Field
        061216|Follow
        48|Forget
        162767|Future
        58|Gain
        1659677889|Government,City,Civilization,Structure
        4989|Grow
        0609276a7a9a|Harm
        060937386a7a8a9a|Harmony,Peace
        387a8a|Have
        59788a9a|Help
        16176978|Hide
        363969|I,Me
        27|Ignore
        6a898a9a|Imperfect
        686a898a9a|Imperfect
        166a7a|Improve
        3a898a9a|Impure
        16486a899a|Intelligence
        0a3a4548598a9a|Interrupt
        163445596a9a|Journey
        3a676a7a898a9a|Key
        36396a9a|Knowledge
        7889|L
        05384548|Lead
        0105162748596789|Legacy
        6a9a|Less
        0116496a9a|Liberate
        676a7a899a|Lie
        15|Link
        16496a898a|Live Again,Reincarnate
        17|Lose
        17497a9a|Message,Notification
        383a899a|Mind
        7a8a|More
        0609596989|Mystery
        677a899a|N
        06090a3a6a9a|N'zeer
        2748676989|Nature
        1516242748596a7a8a9a|Nemesis
        2767|New
        6769|Not,Inside
        343a488a|Nourish
        5989|Old
        373878|Open,Accept
        010512233437384578|Open All
        1216274548596978|Portal
        485989|Past
        0a488a|Path
        0a232734487a8a|Perfection,Balance
        060927486a7a8a9a|Perspective
        0a12277a|Potential
        3738676a78899a|Presence
        677889|Present,Now
        0a676a7a|Pure,Purity
        060959|Pursue
        0a48899a|Chase
        066989|Question
        27697a9a|React
        1216586a8a|Rebel
        050a599a|Recharge,Repair
        2667|Reduce
        090a383a69|Resist,Resistance,Struggle
        377a|Response
        2327597a9a|Restraint
        0626|Retreat
        264969|Safety
        177a8a|Save,Rescue
        09|See
        696a7889|Seek,Search
        2334|Self,Individual
        2759676a898a|Separate
        060927486789|Shapers,Collective
        27344878|Share
        060937386789|Shield
        09377a9a|Signal
        78|Simple
        373a676a|Soul
        274878|Stability,Stay
        0a16273a48596a7a8a9a|Star
        67697889|Strong
        0a1216273a6a7a|Sustain
        01050a12162327343a456a7a|Sustain All
        16276a7a898a9a|Technology
        0878|Them
        48696a8a9a|Together
        676a7a898a9a|Truth
        010517233445696a7889|Unbounded
        177a|Use
        06093639|Victory
        373848|Want
        3669|We,Us
        596769|Weak
        17587a8a|Worth
        67697a898a|XM
        070878|You,Your,Other
    """
}
