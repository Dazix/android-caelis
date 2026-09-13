package com.dazix.caelis.ar

/**
 * Lock-free, zero-allocation circular array storing timestamped rotation vectors.
 * Capacity is fixed to a power of 2 (default 128) for zero-branch bitmask indexing.
 *
 * Thread-safe for single-producer (Sensor event listener) and single-consumer
 * (Camera analysis / UI render thread) without blocking locks.
 */
class SensorTimestampRingBuffer(val capacity: Int = 128) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "capacity must be a power of 2, got $capacity"
        }
    }

    private val mask = capacity - 1
    private val timestamps = LongArray(capacity)
    private val quaternions = FloatArray(capacity * 4)

    @Volatile
    private var writeHead = 0L

    /**
     * Records a rotation vector sample at [timestampNs].
     * Single-producer thread (SensorEventListener).
     */
    fun push(timestampNs: Long, qx: Float, qy: Float, qz: Float, qw: Float) {
        val index = (writeHead and mask.toLong()).toInt()
        timestamps[index] = timestampNs
        val qIndex = index * 4
        quaternions[qIndex] = qx
        quaternions[qIndex + 1] = qy
        quaternions[qIndex + 2] = qz
        quaternions[qIndex + 3] = qw
        writeHead++
    }

    /**
     * Finds the two sensor readings bracketing [targetTimestampNs].
     *
     * @param outQ1 4-element FloatArray receiving quaternion before/at target.
     * @param outQ2 4-element FloatArray receiving quaternion after target.
     * @param outTimestamps 2-element LongArray receiving [t1, t2].
     * @return true if target was successfully bracketed or clamped to nearest valid sample.
     */
    fun findBracket(
        targetTimestampNs: Long,
        outQ1: FloatArray,
        outQ2: FloatArray,
        outTimestamps: LongArray,
    ): Boolean {
        val currentHead = writeHead
        if (currentHead == 0L) return false

        val available = minOf(currentHead, capacity.toLong()).toInt()
        val tail = currentHead - available

        val oldestIndex = (tail and mask.toLong()).toInt()
        val newestIndex = ((currentHead - 1) and mask.toLong()).toInt()

        val oldestTime = timestamps[oldestIndex]
        val newestTime = timestamps[newestIndex]

        // Target before oldest recorded sample -> Clamp to oldest
        if (targetTimestampNs <= oldestTime) {
            copyQuaternion(oldestIndex, outQ1)
            copyQuaternion(oldestIndex, outQ2)
            outTimestamps[0] = oldestTime
            outTimestamps[1] = oldestTime
            return true
        }

        // Target after newest recorded sample -> Clamp to newest
        if (targetTimestampNs >= newestTime) {
            copyQuaternion(newestIndex, outQ1)
            copyQuaternion(newestIndex, outQ2)
            outTimestamps[0] = newestTime
            outTimestamps[1] = newestTime
            return true
        }

        // Binary search within linear logical range [0, available - 1]
        var low = 0
        var high = available - 1
        var bestIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val actualIndex = ((tail + mid) and mask.toLong()).toInt()
            val time = timestamps[actualIndex]

            if (time == targetTimestampNs) {
                copyQuaternion(actualIndex, outQ1)
                copyQuaternion(actualIndex, outQ2)
                outTimestamps[0] = time
                outTimestamps[1] = time
                return true
            }

            if (time < targetTimestampNs) {
                bestIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val idx1 = ((tail + bestIndex) and mask.toLong()).toInt()
        val idx2 = ((tail + minOf(bestIndex + 1, available - 1)) and mask.toLong()).toInt()

        copyQuaternion(idx1, outQ1)
        copyQuaternion(idx2, outQ2)
        outTimestamps[0] = timestamps[idx1]
        outTimestamps[1] = timestamps[idx2]
        return true
    }

    private fun copyQuaternion(slot: Int, out: FloatArray) {
        val qIndex = slot * 4
        out[0] = quaternions[qIndex]
        out[1] = quaternions[qIndex + 1]
        out[2] = quaternions[qIndex + 2]
        out[3] = quaternions[qIndex + 3]
    }
}
