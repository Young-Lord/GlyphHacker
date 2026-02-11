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
            val segment = eulerPath(componentEdges)
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

    private fun eulerPath(edges: Set<GlyphEdge>): List<Int> {
        if (edges.isEmpty()) {
            return emptyList()
        }

        val adjacency = mutableMapOf<Int, MutableMap<Int, Int>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.a) { mutableMapOf() }.increment(edge.b)
            adjacency.getOrPut(edge.b) { mutableMapOf() }.increment(edge.a)
        }

        val oddNodes = adjacency
            .filterValues { neighbors -> neighbors.values.sum() % 2 == 1 }
            .keys
            .sorted()
        val startNode = oddNodes.firstOrNull() ?: adjacency.keys.minOrNull() ?: return emptyList()

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
