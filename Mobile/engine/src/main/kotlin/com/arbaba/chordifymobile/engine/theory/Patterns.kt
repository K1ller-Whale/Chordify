package com.arbaba.chordifymobile.engine.theory

// Repeating progressions from chordify_core/theory.py.

data class ProgressionPattern(val roman: List<String>, val name: String?, val count: Int)

// Several rotations of a loop can have names (I–V–vi–IV and vi–IV–I–V); this order decides
// which one is shown, so a loop is always presented the same way.
val NAMED_PROGRESSIONS: Map<List<String>, String> = linkedMapOf(
    listOf("I", "V", "vi", "IV") to "I–V–vi–IV (the “Axis” progression)",
    listOf("I", "vi", "IV", "V") to "I–vi–IV–V (the ’50s progression)",
    listOf("vi", "IV", "I", "V") to "vi–IV–I–V (Axis, minor rotation)",
    listOf("I", "IV", "V", "IV") to "I–IV–V–IV (rock three-chord loop)",
    listOf("ii", "V", "I") to "ii–V–I",
    listOf("i", "VII", "VI", "V") to "i–VII–VI–V (Andalusian cadence)",
    listOf("I", "bVII", "IV", "I") to "I–bVII–IV–I (Mixolydian vamp)",
    listOf("I", "bVII", "IV") to "I–bVII–IV (Mixolydian vamp)",
    listOf("I", "IV", "V") to "I–IV–V (three-chord trick)",
)

// Python's ordering of tuples of strings.
private val LEXICOGRAPHIC = Comparator<List<String>> { a, b ->
    for (i in 0 until minOf(a.size, b.size)) {
        val c = a[i].compareTo(b[i])
        if (c != 0) return@Comparator c
    }
    a.size - b.size
}

private fun rotations(pattern: List<String>): List<List<String>> = pattern.indices.map { pattern.drop(it) + pattern.take(it) }

/** True if [small] is a contiguous piece of [loop] played cyclically. */
private fun insideLoop(small: List<String>, loop: List<String>): Boolean {
    val doubled = loop + loop
    return small.size <= loop.size && loop.indices.any { doubled.subList(it, it + small.size) == small }
}

/** Repeating loops in a chord-change sequence (Roman numerals), rotations merged, most frequent first. */
fun findPatterns(numerals: List<String?>, lengths: List<Int> = listOf(4, 3), minCount: Int = 3,
                 limit: Int = 3): List<ProgressionPattern> {
    val base = numerals.filter { !it.isNullOrEmpty() }.map { baseNumeral(it)!! }
    val seq = base.filterIndexed { i, n -> i == 0 || base[i - 1] != n }
    val found = ArrayList<ProgressionPattern>()
    val covered = LinkedHashSet<List<String>>()
    for (length in lengths) {
        val grams = LinkedHashMap<List<String>, Int>()
        for (i in 0..seq.size - length) {
            val gram = seq.subList(i, i + length).toList()
            grams[gram] = (grams[gram] ?: 0) + 1
        }
        val classes = LinkedHashMap<List<String>, LinkedHashMap<List<String>, Int>>()
        for ((gram, count) in grams) {
            if (gram.toSet().size < 2) continue
            val canonical = rotations(gram).minWithOrNull(LEXICOGRAPHIC)!!
            classes.getOrPut(canonical) { LinkedHashMap() }[gram] = count
        }
        for ((canonical, members) in classes) {
            var (rotation, count) = members.entries.first().toPair()
            for ((gram, n) in members) if (n > count) { rotation = gram; count = n }
            if (count < minCount || covered.any { insideLoop(rotation, it) }) continue
            val rotationSet = rotations(rotation).toSet()
            val named = NAMED_PROGRESSIONS.keys.firstOrNull { it in rotationSet }
            if (named != null) rotation = named
            found.add(ProgressionPattern(rotation, named?.let { NAMED_PROGRESSIONS.getValue(it) }, count))
            covered.add(canonical)
        }
    }
    return found.sortedWith(compareByDescending<ProgressionPattern> { it.count }.thenByDescending { it.roman.size })
        .take(limit)
}
