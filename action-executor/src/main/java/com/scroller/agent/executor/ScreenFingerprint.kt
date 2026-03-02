package com.scroller.agent.executor

class ScreenFingerprint private constructor(
    private val bits: LongArray,
    private val size: Int
) {
    fun similarity(other: ScreenFingerprint): Float {
        require(size == other.size) { "Fingerprint sizes must match" }
        var distance = 0
        for (i in bits.indices) {
            distance += java.lang.Long.bitCount(bits[i] xor other.bits[i])
        }
        val totalBits = size * size
        return 1.0f - (distance.toFloat() / totalBits.toFloat())
    }

    companion object {
        fun fromLuminance(luminance: IntArray, size: Int): ScreenFingerprint {
            require(luminance.size == size * size) { "Luminance array size must match dimensions" }
            var sum = 0L
            for (value in luminance) {
                sum += value.toLong()
            }
            val avg = (sum / luminance.size).toInt()
            val totalBits = size * size
            val longSize = (totalBits + 63) / 64
            val bitset = LongArray(longSize)

            for (i in luminance.indices) {
                if (luminance[i] > avg) {
                    val wordIndex = i / 64
                    val bitIndex = i % 64
                    bitset[wordIndex] = bitset[wordIndex] or (1L shl bitIndex)
                }
            }

            return ScreenFingerprint(bitset, size)
        }
    }
}
