package space.cherryband.digitaldetox

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit

/*
 * Copyright (c) 2019 Pierantonio Cangianiello
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */ /**
 * A HashMap which entries expires after the specified life time.
 * The life-time can be defined on a per-key basis, or using a default one, that is passed to the
 * constructor.
 *
 * @author Pierantonio Cangianiello
 * @param <K> the Key type
 * @param <V> the Value type
</V></K> */
class SelfExpiringHashMap<K, V> : MutableMap<K, V?> {
    private val internalMap: MutableMap<K, V?>
    private val expiringKeys: MutableMap<K, ExpiringKey<K>>

    /**
     * Holds the map keys using the given life time for expiration.
     */
    private val delayQueue = DelayQueue<ExpiringKey<K>?>()

    /**
     * The default max life time in milliseconds.
     */
    private val maxLifeTimeMillis: Long

    constructor() {
        internalMap = ConcurrentHashMap()
        expiringKeys = WeakHashMap()
        maxLifeTimeMillis = Long.MAX_VALUE
    }

    constructor(defaultMaxLifeTimeMillis: Long) {
        internalMap = ConcurrentHashMap()
        expiringKeys = WeakHashMap()
        maxLifeTimeMillis = defaultMaxLifeTimeMillis
    }

    constructor(defaultMaxLifeTimeMillis: Long, initialCapacity: Int) {
        internalMap = ConcurrentHashMap(initialCapacity)
        expiringKeys = WeakHashMap(initialCapacity)
        maxLifeTimeMillis = defaultMaxLifeTimeMillis
    }

    constructor(defaultMaxLifeTimeMillis: Long, initialCapacity: Int, loadFactor: Float) {
        internalMap = ConcurrentHashMap(initialCapacity, loadFactor)
        expiringKeys = WeakHashMap(initialCapacity, loadFactor)
        maxLifeTimeMillis = defaultMaxLifeTimeMillis
    }

    override val size: Int
        /**
         * {@inheritDoc}
         */
        get() {
            cleanup()
            return internalMap.size
        }

    /**
     * {@inheritDoc}
     */
    override fun isEmpty(): Boolean {
        cleanup()
        return internalMap.isEmpty()
    }

    /**
     * {@inheritDoc}
     */
    override fun containsKey(key: K): Boolean {
        cleanup()
        return internalMap.containsKey(key)
    }

    /**
     * {@inheritDoc}
     */
    override fun containsValue(value: V?): Boolean {
        cleanup()
        return internalMap.containsValue(value)
    }

    override operator fun get(key: K): V? {
        cleanup()
        renewKey(key)
        return internalMap[key]
    }

    /**
     * Associates the given key to the given value in this map, with the specified life
     * times in milliseconds.
     *
     * @param key
     * @param value
     * @return a previously associated object for the given key (if exists).
     */
    override fun put(key: K, value: V?): V? {
        return this.put(key, value, maxLifeTimeMillis)
    }

    /**
     * Associates the given key to the given value in this map, with the specified life
     * times in milliseconds.
     *
     * @param key
     * @param value
     * @param lifeTimeMillis
     * @return a previously associated object for the given key (if exists).
     */
    fun put(key: K, value: V?, lifeTimeMillis: Long): V? {
        cleanup()
        val delayedKey: ExpiringKey<K> = ExpiringKey(key, lifeTimeMillis)
        val oldKey: ExpiringKey<K>? = expiringKeys.put(key, delayedKey)
        if (oldKey != null) {
            expireKey(oldKey)
            expiringKeys[key] = delayedKey
        }
        delayQueue.offer(delayedKey)
        return internalMap.put(key, value)
    }

    /**
     * {@inheritDoc}
     */
    override fun remove(key: K): V? {
        val removedValue = internalMap.remove(key)
        expiringKeys.remove(key)?.let { expireKey(it) }
        return removedValue
    }

    /**
     * Not supported.
     */
    override fun putAll(from: Map<out K, V?>) {
        throw UnsupportedOperationException()
    }

    /**
     * Renews the specified key, setting the life time to the initial value.
     *
     * @param key
     * @return true if the key is found, false otherwise
     */
    fun renewKey(key: K): Boolean {
        val delayedKey = expiringKeys[key]
        if (delayedKey != null) {
            delayedKey.renew()
            return true
        }
        return false
    }

    private fun expireKey(delayedKey: ExpiringKey<K>) {
        delayedKey.expire()
        cleanup()
    }

    /**
     * {@inheritDoc}
     */
    override fun clear() {
        delayQueue.clear()
        expiringKeys.clear()
        internalMap.clear()
    }

    /**
     * Not supported.
     */
    override val keys: MutableSet<K>
        get() { throw UnsupportedOperationException() }
    override val values: MutableCollection<V?>
        get() { throw UnsupportedOperationException() }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V?>>
        get() { throw UnsupportedOperationException() }

    private fun cleanup() {
        var delayedKey: ExpiringKey<K>? = delayQueue.poll()
        while (delayedKey != null) {
            internalMap.remove(delayedKey.key)
            expiringKeys.remove(delayedKey.key)
            delayedKey = delayQueue.poll()
        }
    }

    private inner class ExpiringKey<K>(val key: K?, private val maxLifeTimeMillis: Long) : Delayed {
        private var startTime = System.currentTimeMillis()

        /**
         * {@inheritDoc}
         */
        override fun equals(other: Any?): Boolean {
            if (other == null) {
                return false
            }
            if (javaClass != other.javaClass) {
                return false
            }
            val other = other as ExpiringKey<*>
            return !(key !== other.key && (key == null || key != other.key))
        }

        /**
         * {@inheritDoc}
         */
        override fun hashCode(): Int {
            var hash = 7
            hash = 31 * hash + (key?.hashCode() ?: 0)
            return hash
        }

        /**
         * {@inheritDoc}
         */
        override fun getDelay(unit: TimeUnit): Long {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS)
        }

        private val delayMillis: Long
            get() = startTime + maxLifeTimeMillis - System.currentTimeMillis()

        fun renew() {
            startTime = System.currentTimeMillis()
        }

        fun expire() {
            startTime = Long.MIN_VALUE
        }

        /**
         * {@inheritDoc}
         */
        override fun compareTo(other: Delayed): Int {
            return delayMillis.compareTo((other as ExpiringKey<*>).delayMillis)
        }
    }
}