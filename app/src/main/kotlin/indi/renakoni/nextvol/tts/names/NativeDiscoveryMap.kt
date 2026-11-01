package indi.renakoni.nextvol.tts.names

/* Adapted from LLVM libc++ 18 __hash_table and __functional/hash.h.
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Full license: assets/speech/names/LICENSE-libcxx.txt.
 */

/** The original libc++ bucket/predecessor chain matters when choosing one trie child. */
internal class NativeDiscoveryMap<K : Any, V : Any>(private val hash: (K) -> Long) : Iterable<Pair<K, V>> {
    private class Entry<K, V>(val key: K?, var value: V?, val hash: Long, var next: Entry<K, V>? = null)
    private val head = Entry<K, V>(null, null, 0)
    private var buckets = arrayOfNulls<Entry<K, V>>(0)
    private var size = 0

    operator fun get(key: K): V? {
        if (buckets.isEmpty()) return null
        val hashed = hash(key)
        val bucket = bucket(hashed)
        var node = buckets[bucket]?.next
        while (node != null && bucket(node.hash) == bucket) {
            if (node.hash == hashed && node.key == key) return node.value
            node = node.next
        }
        return null
    }

    fun getValue(key: K): V = get(key)!!

    fun getOrPut(key: K, create: () -> V): V = get(key) ?: create().also { set(key, it) }

    operator fun set(key: K, value: V) {
        val hashed = hash(key)
        if (buckets.isNotEmpty()) {
            val bucket = bucket(hashed)
            var node = buckets[bucket]?.next
            while (node != null && bucket(node.hash) == bucket) {
                if (node.hash == hashed && node.key == key) {
                    node.value = value
                    return
                }
                node = node.next
            }
        }
        if (size + 1 > buckets.size) rehash(nextBucketCount())
        val bucket = bucket(hashed)
        val predecessor = buckets[bucket]
        val node = Entry(key, value, hashed)
        if (predecessor == null) {
            node.next = head.next
            head.next = node
            buckets[bucket] = head
            node.next?.let { buckets[bucket(it.hash)] = node }
        } else {
            node.next = predecessor.next
            predecessor.next = node
        }
        size++
    }

    val values: Iterable<V> get() = Iterable {
        val iterator = iterator()
        object : Iterator<V> {
            override fun hasNext() = iterator.hasNext()
            override fun next() = iterator.next().second
        }
    }

    override fun iterator(): Iterator<Pair<K, V>> = object : Iterator<Pair<K, V>> {
        private var next = head.next
        override fun hasNext() = next != null
        override fun next(): Pair<K, V> {
            val node = next!!
            next = node.next
            return node.key!! to node.value!!
        }
    }

    private fun bucket(hash: Long): Int = if (buckets.size and (buckets.size - 1) == 0)
        (hash and (buckets.size - 1).toLong()).toInt()
    else java.lang.Long.remainderUnsigned(hash, buckets.size.toLong()).toInt()

    private fun nextBucketCount(): Int {
        if (buckets.isEmpty()) return 2
        var candidate = buckets.size * 2 + 1
        while (true) {
            var divisor = 3
            while (divisor.toLong() * divisor <= candidate && candidate % divisor != 0) divisor += 2
            if (divisor.toLong() * divisor > candidate) return candidate
            candidate += 2
        }
    }

    private fun rehash(count: Int) {
        buckets = arrayOfNulls(count)
        var previous = head
        var current = previous.next ?: return
        var previousBucket = bucket(current.hash)
        buckets[previousBucket] = previous
        previous = current
        while (true) {
            current = previous.next ?: break
            val currentBucket = bucket(current.hash)
            if (currentBucket == previousBucket) previous = current
            else {
                val predecessor = buckets[currentBucket]
                if (predecessor == null) {
                    buckets[currentBucket] = previous
                    previous = current
                    previousBucket = currentBucket
                } else {
                    previous.next = current.next
                    current.next = predecessor.next
                    predecessor.next = current
                }
            }
        }
    }
}

/** libc++ CityHash on little-endian wchar_t strings (the original words are at most five code points). */
internal fun nativeDiscoveryWordHash(word: String): Long {
    val points = word.codePoints().toArray()
    val bytes = points.size * 4
    fun load64(index: Int) = points[index / 4].toLong() or (points[index / 4 + 1].toLong() shl 32)
    fun mix(first: Long, second: Long): Long {
        val multiplier = 0x9ddfea08eb382d69UL.toLong()
        var a = (first xor second) * multiplier
        a = a xor (a ushr 47)
        var b = (second xor a) * multiplier
        b = b xor (b ushr 47)
        return b * multiplier
    }
    if (bytes > 16) {
        val a = load64(0) * 0xb492b66fbe98f273UL.toLong()
        val b = load64(8)
        val c = load64(bytes - 8) * 0x9ae16a3b2f90404fUL.toLong()
        val d = load64(bytes - 16) * 0xc3a5c85c97cb3127UL.toLong()
        return mix(java.lang.Long.rotateRight(a - b, 43) + java.lang.Long.rotateRight(c, 30) + d,
            a + java.lang.Long.rotateRight(b xor 0xc949d7c7509e6557UL.toLong(), 20) - c + bytes)
    }
    if (bytes > 8) {
        val b = load64(bytes - 8)
        return mix(load64(0), java.lang.Long.rotateRight(b + bytes, bytes)) xor b
    }
    if (bytes >= 4) return mix(bytes + (points[0].toLong() shl 3), points.last().toLong())
    return 0x9ae16a3b2f90404fUL.toLong()
}
