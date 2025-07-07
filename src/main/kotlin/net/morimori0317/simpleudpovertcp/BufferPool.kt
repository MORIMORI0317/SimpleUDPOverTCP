package net.morimori0317.simpleudpovertcp

import java.util.*

class BufferPool(
    private val size: Int
) {
    private val cache = LinkedList<Entry>()

    @Synchronized
    fun obtain(): Entry {

        // TODO 定期的にキャッシュをクリア

        return if (cache.isNotEmpty()) {
            cache.pop()
        } else {
            Entry()
        }
    }

    @Synchronized
    fun free(entry: Entry) {
        cache.add(entry)
    }

    inner class Entry() {
        val content = ByteArray(size)
    }
}