package forge.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PCM signed 16-bit little-endian mono at 16 kHz -- the format Whisper expects.
 */
private val AUDIO_FORMAT = AudioFormat(16000f, 16, 1, true, false)

/**
 * Records audio from the default microphone until silence is detected or the
 * maximum duration is reached.
 *
 * Silence is determined by comparing the RMS of each 100 ms chunk (in dB
 * relative to full-scale 16-bit) against [silenceThresholdDb]. When the
 * number of consecutive silent chunks spans at least [silenceDurationMs],
 * recording stops.
 *
 * @param maxDurationSec  hard cap on recording length in seconds
 * @param silenceThresholdDb  dB threshold below which audio is considered silence (e.g. -40.0)
 * @param silenceDurationMs  milliseconds of continuous silence required to stop
 * @return a complete WAV file (44-byte header + PCM data) as a byte array
 */
fun recordUntilSilence(
    maxDurationSec: Int = 30,
    silenceThresholdDb: Double = -40.0,
    silenceDurationMs: Int = 1500
): ByteArray {
    val line: TargetDataLine = AudioSystem.getTargetDataLine(AUDIO_FORMAT)
    line.open(AUDIO_FORMAT)
    line.start()

    val output = ByteArrayOutputStream()

    // 100 ms of 16-bit mono @ 16 kHz = 16000 samples/sec * 2 bytes/sample / 10 = 3200 bytes
    val bufferSize = 3200
    val buffer = ByteArray(bufferSize)
    val chunkMs = 100
    val maxChunks = (maxDurationSec * 1000) / chunkMs
    val silenceChunksNeeded = silenceDurationMs / chunkMs
    var consecutiveSilentChunks = 0

    try {
        for (chunkIndex in 0 until maxChunks) {
            val bytesRead = line.read(buffer, 0, bufferSize)
            if (bytesRead <= 0) break

            output.write(buffer, 0, bytesRead)

            // Calculate RMS of this chunk
            val numSamples = bytesRead / 2
            var sumSquares = 0.0
            for (i in 0 until numSamples) {
                val lo = buffer[i * 2].toInt() and 0xFF
                val hi = buffer[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo  // signed 16-bit LE
                sumSquares += (sample.toDouble() * sample.toDouble())
            }
            val rms = sqrt(sumSquares / numSamples)
            val db = if (rms < 1.0) -100.0 else 20.0 * log10(rms / 32768.0)

            if (db < silenceThresholdDb) {
                consecutiveSilentChunks++
            } else {
                consecutiveSilentChunks = 0
            }

            if (consecutiveSilentChunks >= silenceChunksNeeded) {
                break
            }
        }
    } finally {
        line.stop()
        line.close()
    }

    val pcmData = output.toByteArray()
    return writeWavHeader(pcmData)
}

/**
 * Records exactly [durationSec] seconds of audio from the default microphone.
 *
 * @param durationSec number of seconds to record
 * @return a complete WAV file (44-byte header + PCM data) as a byte array
 */
fun recordFixed(durationSec: Int): ByteArray {
    val line: TargetDataLine = AudioSystem.getTargetDataLine(AUDIO_FORMAT)
    line.open(AUDIO_FORMAT)
    line.start()

    // Total bytes = sampleRate * bytesPerSample * channels * seconds
    val totalBytes = 16000 * 2 * 1 * durationSec
    val output = ByteArrayOutputStream(totalBytes)
    val bufferSize = 3200 // 100 ms chunks
    val buffer = ByteArray(bufferSize)
    var remaining = totalBytes

    try {
        while (remaining > 0) {
            val toRead = minOf(bufferSize, remaining)
            val bytesRead = line.read(buffer, 0, toRead)
            if (bytesRead <= 0) break
            output.write(buffer, 0, bytesRead)
            remaining -= bytesRead
        }
    } finally {
        line.stop()
        line.close()
    }

    val pcmData = output.toByteArray()
    return writeWavHeader(pcmData)
}

/**
 * Tests whether the system has a microphone capable of capturing audio in the
 * required format (16 kHz, 16-bit, mono, signed, little-endian).
 *
 * @return true if a compatible capture device is available
 */
fun isMicrophoneAvailable(): Boolean {
    return try {
        val line: TargetDataLine = AudioSystem.getTargetDataLine(AUDIO_FORMAT)
        line.open(AUDIO_FORMAT)
        line.close()
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Prepends a standard 44-byte RIFF/WAV header to raw PCM audio data.
 *
 * @param audioData    raw PCM bytes (signed 16-bit little-endian)
 * @param sampleRate   samples per second (default 16000)
 * @param channels     number of audio channels (default 1 = mono)
 * @param bitsPerSample bits per sample (default 16)
 * @return byte array containing the complete WAV file
 */
private fun writeWavHeader(
    audioData: ByteArray,
    sampleRate: Int = 16000,
    channels: Int = 1,
    bitsPerSample: Int = 16
): ByteArray {
    val dataSize = audioData.size
    val fileSize = dataSize + 36  // total file size minus 8 bytes for "RIFF" + size field
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

    // RIFF chunk descriptor
    header.put('R'.code.toByte())
    header.put('I'.code.toByte())
    header.put('F'.code.toByte())
    header.put('F'.code.toByte())
    header.putInt(fileSize)
    header.put('W'.code.toByte())
    header.put('A'.code.toByte())
    header.put('V'.code.toByte())
    header.put('E'.code.toByte())

    // fmt sub-chunk
    header.put('f'.code.toByte())
    header.put('m'.code.toByte())
    header.put('t'.code.toByte())
    header.put(' '.code.toByte())
    header.putInt(16)                   // sub-chunk size (PCM = 16)
    header.putShort(1.toShort())        // audio format (1 = PCM)
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())

    // data sub-chunk
    header.put('d'.code.toByte())
    header.put('a'.code.toByte())
    header.put('t'.code.toByte())
    header.put('a'.code.toByte())
    header.putInt(dataSize)

    val headerBytes = header.array()
    val result = ByteArray(headerBytes.size + audioData.size)
    System.arraycopy(headerBytes, 0, result, 0, headerBytes.size)
    System.arraycopy(audioData, 0, result, headerBytes.size, audioData.size)
    return result
}
