package com.tunespark.music

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.div
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.text.compareTo
import kotlin.text.get
import kotlin.text.set
import kotlin.times

object VisualizerData {
    val bandLevels = MutableStateFlow(FloatArray(21) { 0f })
}

class VisualizerAudioSink : TeeAudioProcessor.AudioBufferSink {

    private val fftSize = 1024
    private val fft = FloatFFT_1D(fftSize.toLong())
    private val sampleBuffer = FloatArray(fftSize)
    private var bufferIndex = 0
    private var runningMax = 0.05f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        bufferIndex = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toFloat() / Short.MAX_VALUE
            sampleBuffer[bufferIndex] = sample
            bufferIndex++
            if (bufferIndex >= fftSize) {
                processFft()
                bufferIndex = 0
            }
        }
    }

    private fun processFft() {
        val fftInput = sampleBuffer.copyOf()
        fft.realForward(fftInput)

        val barCount = 21
        val newLevels = FloatArray(barCount)
        val binsPerBar = (fftSize / 2) / barCount

        for (bar in 0 until barCount) {
            var sum = 0f
            for (j in 0 until binsPerBar) {
                val idx = (bar * binsPerBar + j) * 2 + 2   // +2 skips the DC bin (index 0)
                if (idx + 1 < fftInput.size) {
                    val re = fftInput[idx]
                    val im = fftInput[idx + 1]
                    sum += sqrt(re * re + im * im)
                }
            }
            val avgMagnitude = sum / binsPerBar
            val instantMax = fftInput.maxOrNull() ?: 0.05f
            runningMax = (runningMax * 0.98f).coerceAtLeast(avgMagnitude).coerceAtLeast(0.02f)
            newLevels[bar] = (avgMagnitude / runningMax).coerceIn(0f, 1f)
            // ... rest of your existing normalization logic stays the same
        }
        VisualizerData.bandLevels.value = newLevels
    }
}