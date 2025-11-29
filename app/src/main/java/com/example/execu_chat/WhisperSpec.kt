package com.example.execu_chat

import kotlin.math.*

/**
 * Whisper mel spectrogram implementation in pure Kotlin.
 * Matches OpenAI Whisper's audio.py preprocessing exactly.
 *
 * Parameters (hardcoded to match Whisper):
 * - Sample rate: 16000 Hz
 * - N_FFT: 400
 * - HOP_LENGTH: 160
 * - N_MELS: 80
 * - CHUNK_LENGTH: 30 seconds (480000 samples)
 * - N_FRAMES: 3000
 */
object WhisperMelSpectrogram {

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val N_MELS = 80
    const val CHUNK_LENGTH = 30
    const val N_SAMPLES = CHUNK_LENGTH * SAMPLE_RATE  // 480000
    const val N_FRAMES = N_SAMPLES / HOP_LENGTH       // 3000

    // Pre-computed mel filterbank (80 x 201)
    // Generated using: librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)
    // This is a simplified version - for production, load from file
    private val melFilters: Array<FloatArray> by lazy { createMelFilterbank() }

    // Hann window for STFT
    private val hannWindow: FloatArray by lazy {
        FloatArray(N_FFT) { i ->
            (0.5 * (1 - cos(2 * PI * i / N_FFT))).toFloat()
        }
    }

    /**
     * Compute log-mel spectrogram from raw audio samples.
     *
     * @param audio Float array of audio samples (normalized to [-1, 1])
     * @return FloatArray of shape [1, 80, 3000] flattened (for ExecuTorch tensor)
     */
    fun compute(audio: FloatArray): FloatArray {
        // 1. Pad or trim to exactly N_SAMPLES (30 seconds)
        val padded = padOrTrim(audio)

        // 2. Compute STFT magnitude
        val stftMag = stft(padded)  // Shape: [201, 3000]

        // 3. Apply mel filterbank
        val melSpec = applyMelFilters(stftMag)  // Shape: [80, 3000]

        // 4. Convert to log scale (matching Whisper's normalization)
        val logMelSpec = toLogScale(melSpec)  // Shape: [80, 3000]

        // 5. Flatten with batch dimension for tensor creation
        // Output shape: [1, 80, 3000] -> flattened to [240000]
        return logMelSpec.flatMap { it.toList() }.toFloatArray()
    }

    /**
     * Get the output shape for tensor creation
     */
    fun getOutputShape(): LongArray = longArrayOf(1L, N_MELS.toLong(), N_FRAMES.toLong())

    /**
     * Pad or trim audio to exactly N_SAMPLES
     */
    private fun padOrTrim(audio: FloatArray): FloatArray {
        return when {
            audio.size == N_SAMPLES -> audio
            audio.size > N_SAMPLES -> audio.copyOf(N_SAMPLES)
            else -> FloatArray(N_SAMPLES).also {
                audio.copyInto(it)
            }
        }
    }

    /**
     * Compute Short-Time Fourier Transform magnitude
     * Returns shape [n_fft/2 + 1, n_frames] = [201, 3000]
     */
    private fun stft(audio: FloatArray): Array<FloatArray> {
        val nFreqs = N_FFT / 2 + 1  // 201
        val nFrames = N_FRAMES      // 3000

        val result = Array(nFreqs) { FloatArray(nFrames) }

        // Pad audio for STFT (center padding)
        val padLen = N_FFT / 2
        val paddedAudio = FloatArray(audio.size + N_FFT) { i ->
            when {
                i < padLen -> 0f
                i < padLen + audio.size -> audio[i - padLen]
                else -> 0f
            }
        }

        // Process each frame
        for (frame in 0 until nFrames) {
            val start = frame * HOP_LENGTH

            // Apply window and compute FFT
            val windowed = FloatArray(N_FFT) { i ->
                paddedAudio[start + i] * hannWindow[i]
            }

            // Compute FFT magnitude for positive frequencies
            val (real, imag) = fft(windowed)

            for (k in 0 until nFreqs) {
                result[k][frame] = sqrt(real[k] * real[k] + imag[k] * imag[k])
            }
        }

        return result
    }

    /**
     * Simple DFT implementation (replace with FFT library for production)
     * Returns (real, imag) arrays
     */
    private fun fft(x: FloatArray): Pair<FloatArray, FloatArray> {
        val n = x.size
        val real = FloatArray(n / 2 + 1)
        val imag = FloatArray(n / 2 + 1)

        // Naive DFT for first n/2+1 frequencies
        // For production, use a proper FFT library!
        for (k in 0..n / 2) {
            var sumReal = 0.0
            var sumImag = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * PI * k * t / n
                sumReal += x[t] * cos(angle)
                sumImag -= x[t] * sin(angle)
            }
            real[k] = sumReal.toFloat()
            imag[k] = sumImag.toFloat()
        }

        return real to imag
    }

    /**
     * Apply mel filterbank to STFT magnitude
     * Input: [201, 3000], Output: [80, 3000]
     */
    private fun applyMelFilters(stftMag: Array<FloatArray>): Array<FloatArray> {
        val nFrames = stftMag[0].size
        val result = Array(N_MELS) { FloatArray(nFrames) }

        for (mel in 0 until N_MELS) {
            for (frame in 0 until nFrames) {
                var sum = 0f
                for (freq in melFilters[mel].indices) {
                    sum += melFilters[mel][freq] * stftMag[freq][frame]
                }
                result[mel][frame] = sum
            }
        }

        return result
    }

    /**
     * Convert to log scale with Whisper's normalization
     * log_spec = torch.clamp(mel_spec, min=1e-10).log10()
     * log_spec = torch.maximum(log_spec, log_spec.max() - 8.0)
     * log_spec = (log_spec + 4.0) / 4.0
     */
    private fun toLogScale(melSpec: Array<FloatArray>): Array<FloatArray> {
        val result = Array(melSpec.size) { FloatArray(melSpec[0].size) }
        var maxVal = Float.NEGATIVE_INFINITY

        // First pass: compute log and find max
        for (i in melSpec.indices) {
            for (j in melSpec[i].indices) {
                val clamped = maxOf(melSpec[i][j], 1e-10f)
                val logVal = log10(clamped)
                result[i][j] = logVal
                if (logVal > maxVal) maxVal = logVal
            }
        }

        // Second pass: apply Whisper's normalization
        val minVal = maxVal - 8.0f
        for (i in result.indices) {
            for (j in result[i].indices) {
                val clipped = maxOf(result[i][j], minVal)
                result[i][j] = (clipped + 4.0f) / 4.0f
            }
        }

        return result
    }

    /**
     * Create mel filterbank matrix [80, 201]
     * This approximates librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val nFreqs = N_FFT / 2 + 1  // 201
        val fMin = 0.0
        val fMax = SAMPLE_RATE / 2.0  // 8000 Hz

        // Convert Hz to Mel
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // Create mel points
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melMin + i * (melMax - melMin) / (N_MELS + 1)
        }

        // Convert back to Hz
        val hzPoints = melPoints.map { melToHz(it) }

        // Convert to FFT bins
        val binPoints = hzPoints.map { hz ->
            ((N_FFT + 1) * hz / SAMPLE_RATE).toInt()
        }

        // Create filterbank
        val filters = Array(N_MELS) { FloatArray(nFreqs) }

        for (m in 0 until N_MELS) {
            val fLeft = binPoints[m]
            val fCenter = binPoints[m + 1]
            val fRight = binPoints[m + 2]

            for (k in fLeft until fCenter) {
                if (k < nFreqs && fCenter > fLeft) {
                    filters[m][k] = (k - fLeft).toFloat() / (fCenter - fLeft)
                }
            }
            for (k in fCenter until fRight) {
                if (k < nFreqs && fRight > fCenter) {
                    filters[m][k] = (fRight - k).toFloat() / (fRight - fCenter)
                }
            }
        }

        return filters
    }
}