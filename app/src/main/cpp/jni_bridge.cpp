#include <jni.h>
#include <android/log.h>
#include "librosa.h"
#include <vector>

#define LOG_TAG "MelSpectrogram"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNICALL
Java_com_example_execu_1chat_MelSpectrogramNative_computeMelSpectrogram(
        JNIEnv *env,
        jobject /* this */,
        jfloatArray audio_samples,
        jint sample_rate,
        jint n_mels
) {
    // Get audio data from Java
    jsize num_samples = env->GetArrayLength(audio_samples);
    jfloat *samples = env->GetFloatArrayElements(audio_samples, nullptr);

    LOGI("Computing mel spectrogram: %d samples, %d Hz, %d mels",
         num_samples, sample_rate, n_mels);

    // Convert to std::vector for librosa
    std::vector<float> audio_vector(samples, samples + num_samples);

    // Whisper parameters (matching whisper_preprocess.pte)
    int n_fft = 400;
    int n_hop = 160;
    std::string window = "hann";
    bool center = true;
    std::string mode = "reflect";
    float power = 2.0f;
    int fmin = 0;
    int fmax = 8000;

    // Compute mel spectrogram using LibrosaCpp
    std::vector<std::vector<float>> mel_spec = librosa::Feature::melspectrogram(
            audio_vector,
            sample_rate,
            n_fft,
            n_hop,
            window,
            center,
            mode,
            power,
            n_mels,
            fmin,
            fmax
    );

    // mel_spec is [n_mels][n_frames]
    int n_frames = mel_spec[0].size();
    int output_size = n_mels * n_frames;

    LOGI("Output shape: [%d, %d] = %d total values", n_mels, n_frames, output_size);

    // Flatten to row-major order
    std::vector<float> flattened(output_size);
    for (int i = 0; i < n_mels; i++) {
        for (int j = 0; j < n_frames; j++) {
            flattened[i * n_frames + j] = mel_spec[i][j];
        }
    }

    // Convert to Java array
    jfloatArray result = env->NewFloatArray(output_size);
    env->SetFloatArrayRegion(result, 0, output_size, flattened.data());

    // Cleanup
    env->ReleaseFloatArrayElements(audio_samples, samples, 0);

    return result;
}