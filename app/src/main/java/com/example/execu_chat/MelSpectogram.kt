package com.example.execu_chat

class MelSpectogramNative {

    companion object {
        init {
            System.loadLibrary("melspectrogram")
        }
    }

    /**
     * Compute mel spectrogram from audio samples
     * @param audioSamples Raw PCM audio samples (Float array)
     * @param sampleRate Sample rate (16000 for Whisper)
     * @param nMels Number of mel bins (80 for Whisper)
     * @return Flattened mel spectrogram [n_mels * n_frames]
     */
    external fun computeMelSpectrogram(
        audioSamples: FloatArray,
        sampleRate: Int,
        nMels: Int
    ): FloatArray
}