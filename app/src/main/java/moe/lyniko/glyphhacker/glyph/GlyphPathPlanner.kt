package moe.lyniko.glyphhacker.glyph

object GlyphPathPlanner {

    fun buildStrokeSegments(definition: GlyphDefinition): List<List<Int>> {
        val predefined = definition.strokePlan
        if (!predefined.isNullOrEmpty()) {
            return listOf(predefined)
        }
        return buildStrokeSegments(definition.edges)
    }

    fun buildStrokeSegments(edges: Set<GlyphEdge>): List<List<Int>> {
        if (edges.isEmpty()) {
            return emptyList()
        }

        val remaining = edges.toMutableSet()
        val segments = mutableListOf<List<Int>>()
        while (remaining.isNotEmpty()) {
            val componentEdges = collectConnectedComponent(remaining.first(), remaining)
            val segment = buildSingleStrokeRoute(componentEdges)
            if (segment.size >= 2) {
                segments += segment
            }
            remaining.removeAll(componentEdges)
        }
        return segments
    }

    private fun collectConnectedComponent(seed: GlyphEdge, edges: Set<GlyphEdge>): Set<GlyphEdge> {
        val result = LinkedHashSet<GlyphEdge>()
        val queue = ArrayDeque<Int>()
        val visitedNodes = mutableSetOf<Int>()
        queue += seed.a
        queue += seed.b
        visitedNodes += seed.a
        visitedNodes += seed.b

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            edges.forEach { edge ->
                val linked = when (node) {
                    edge.a -> edge.b
                    edge.b -> edge.a
                    else -> null
                }
                if (linked != null) {
                    result += edge
                    if (visitedNodes.add(linked)) {
                        queue += linked
                    }
                }
            }
        }
        return result
    }

    private fun buildSingleStrokeRoute(edges: Set<GlyphEdge>): List<Int> {
        if (edges.isEmpty()) {
            return emptyList()
        }

        val adjacencyCounts = buildAdjacencyCounts(edges)
        val oddNodes = findOddNodes(adjacencyCounts)
        if (oddNodes.size > 2) {
            val simpleAdjacency = buildSimpleAdjacency(edges)
            val augmentingPaths = buildAugmentingPaths(oddNodes, simpleAdjacency)
            augmentingPaths.forEach { path ->
                addPathToAdjacency(adjacencyCounts, path)
            }
        }

        val startNode = when {
            oddNodes.size == 2 -> oddNodes.first()
            oddNodes.isNotEmpty() -> oddNodes.first()
            else -> adjacencyCounts.keys.minOrNull()
        } ?: return emptyList()
        return eulerRouteFromAdjacency(adjacencyCounts, startNode)
    }

    private fun buildAdjacencyCounts(edges: Set<GlyphEdge>): MutableMap<Int, MutableMap<Int, Int>> {
        val adjacency = mutableMapOf<Int, MutableMap<Int, Int>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.a) { mutableMapOf() }.increment(edge.b)
            adjacency.getOrPut(edge.b) { mutableMapOf() }.increment(edge.a)
        }
        return adjacency
    }

    private fun buildSimpleAdjacency(edges: Set<GlyphEdge>): Map<Int, Set<Int>> {
        val adjacency = mutableMapOf<Int, MutableSet<Int>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.a) { mutableSetOf() }.add(edge.b)
            adjacency.getOrPut(edge.b) { mutableSetOf() }.add(edge.a)
        }
        return adjacency
    }

    private fun findOddNodes(adjacency: Map<Int, Map<Int, Int>>): List<Int> {
        return adjacency
            .mapValues { (_, neighbors) -> neighbors.values.sum() }
            .filterValues { degree -> degree % 2 == 1 }
            .keys
            .sorted()
    }

    private fun buildAugmentingPaths(
        oddNodes: List<Int>,
        adjacency: Map<Int, Set<Int>>,
    ): List<List<Int>> {
        if (oddNodes.size <= 2) {
            return emptyList()
        }

        val bfsResults = oddNodes.associateWith { bfsFrom(it, adjacency) }
        val size = oddNodes.size
        val distances = Array(size) { IntArray(size) }
        for (i in 0 until size) {
            val result = bfsResults[oddNodes[i]] ?: continue
            for (j in 0 until size) {
                distances[i][j] = result.distance[oddNodes[j]] ?: (Int.MAX_VALUE / 4)
            }
        }

        val pairs = computeMinimumPairing(oddNodes, distances)
        return pairs.mapNotNull { (start, end) ->
            val previous = bfsResults[start]?.previous ?: return@mapNotNull null
            buildPathFromPrev(previous, start, end)
        }
    }

    private data class BfsResult(
        val distance: Map<Int, Int>,
        val previous: Map<Int, Int?>,
    )

    private fun bfsFrom(start: Int, adjacency: Map<Int, Set<Int>>): BfsResult {
        val distance = mutableMapOf<Int, Int>()
        val previous = mutableMapOf<Int, Int?>()
        val queue = ArrayDeque<Int>()
        queue += start
        distance[start] = 0
        previous[start] = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nextDistance = distance.getValue(node) + 1
            adjacency[node].orEmpty().forEach { neighbor ->
                if (!distance.containsKey(neighbor)) {
                    distance[neighbor] = nextDistance
                    previous[neighbor] = node
                    queue += neighbor
                }
            }
        }

        return BfsResult(distance, previous)
    }

    private fun buildPathFromPrev(previous: Map<Int, Int?>, start: Int, end: Int): List<Int> {
        if (start == end) {
            return listOf(start)
        }
        if (!previous.containsKey(end)) {
            return listOf(start, end)
        }

        val path = mutableListOf<Int>()
        var current: Int? = end
        while (current != null) {
            path += current
            current = previous[current]
        }
        path.reverse()
        return path
    }

    private fun computeMinimumPairing(
        oddNodes: List<Int>,
        distances: Array<IntArray>,
    ): List<Pair<Int, Int>> {
        val size = oddNodes.size
        val fullMask = (1 shl size) - 1
        val memo = IntArray(1 shl size) { -1 }
        val choice = IntArray(1 shl size) { -1 }

        fun solve(mask: Int): Int {
            if (mask == 0) return 0
            val cached = memo[mask]
            if (cached >= 0) return cached
            val i = Integer.numberOfTrailingZeros(mask)
            var best = Int.MAX_VALUE / 4
            var bestJ = -1
            var remaining = mask and (1 shl i).inv()
            while (remaining != 0) {
                val j = Integer.numberOfTrailingZeros(remaining)
                val cost = distances[i][j] + solve(remaining and (1 shl j).inv())
                if (cost < best) {
                    best = cost
                    bestJ = j
                }
                remaining = remaining and (remaining - 1)
            }
            memo[mask] = best
            choice[mask] = bestJ
            return best
        }

        solve(fullMask)
        val pairs = mutableListOf<Pair<Int, Int>>()
        var mask = fullMask
        while (mask != 0) {
            val i = Integer.numberOfTrailingZeros(mask)
            val j = choice[mask]
            if (j < 0) break
            pairs += oddNodes[i] to oddNodes[j]
            mask = mask and (1 shl i).inv() and (1 shl j).inv()
        }
        return pairs
    }

    private fun addPathToAdjacency(
        adjacency: MutableMap<Int, MutableMap<Int, Int>>,
        path: List<Int>,
    ) {
        if (path.size < 2) return
        for (index in 1 until path.size) {
            val from = path[index - 1]
            val to = path[index]
            adjacency.getOrPut(from) { mutableMapOf() }.increment(to)
            adjacency.getOrPut(to) { mutableMapOf() }.increment(from)
        }
    }

    private fun eulerRouteFromAdjacency(
        adjacency: MutableMap<Int, MutableMap<Int, Int>>,
        startNode: Int,
    ): List<Int> {
        val stack = ArrayDeque<Int>()
        val route = mutableListOf<Int>()
        stack += startNode

        while (stack.isNotEmpty()) {
            val current = stack.last()
            val next = adjacency[current]
                ?.entries
                ?.firstOrNull { (_, count) -> count > 0 }
                ?.key

            if (next == null) {
                route += stack.removeLast()
            } else {
                adjacency[current]?.decrement(next)
                adjacency[next]?.decrement(current)
                stack += next
            }
        }

        return route.reversed()
    }

    private fun MutableMap<Int, Int>.increment(key: Int) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun MutableMap<Int, Int>.decrement(key: Int) {
        val value = (this[key] ?: 0) - 1
        if (value <= 0) {
            remove(key)
        } else {
            this[key] = value
        }
    }
}
