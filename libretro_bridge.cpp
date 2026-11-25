#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
#include "libretro.h"

#define LOG_TAG "RetroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    void *g_core_handle = nullptr;

    void (*core_init)() = nullptr;
    void (*core_deinit)() = nullptr;
    void (*core_set_environment)(retro_environment_t) = nullptr;
    void (*core_set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*core_set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*core_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*core_set_input_poll)(retro_input_poll_t) = nullptr;
    void (*core_set_input_state)(retro_input_state_t) = nullptr;
    bool (*core_load_game)(const struct retro_game_info *) = nullptr;
    void (*core_unload_game)() = nullptr;
    void (*core_run)() = nullptr;
    void (*core_get_system_av_info)(struct retro_system_av_info *) = nullptr;
    size_t (*core_serialize_size)() = nullptr;
    bool (*core_serialize)(void *, size_t) = nullptr;
    bool (*core_unserialize)(const void *, size_t) = nullptr;

    std::string g_system_dir;
    std::string g_save_dir;
    retro_pixel_format g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;

    std::mutex g_av_mutex;
    std::mutex g_core_mutex;
    std::vector<int32_t> g_video_buffer;
    unsigned g_video_width = 0;
    unsigned g_video_height = 0;
    std::vector<int16_t> g_audio_buffer;
    uint32_t g_input_mask = 0;
    int16_t g_pointer_x = 0;
    int16_t g_pointer_y = 0;
    bool g_pointer_pressed = false;
    double g_sample_rate = 44100.0;

    bool env_callback(unsigned cmd, void *data) {
        switch (cmd) {
            case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
                *static_cast<bool *>(data) = true;
                return true;
            }
            case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
                g_pixel_format = *static_cast<retro_pixel_format *>(data);
                return true;
            }
            case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
                *static_cast<const char **>(data) = g_system_dir.c_str();
                return true;
            }
            case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
                *static_cast<const char **>(data) = g_save_dir.c_str();
                return true;
            }
            default:
                return false;
        }
    }

    void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
        if (!data || width == 0 || height == 0) return;

        std::lock_guard<std::mutex> lock(g_av_mutex);
        g_video_width = width;
        g_video_height = height;
        g_video_buffer.resize(static_cast<size_t>(width) * height);

        const auto *src_bytes = static_cast<const uint8_t *>(data);
        for (unsigned y = 0; y < height; ++y) {
            const uint8_t *row = src_bytes + y * pitch;
            int32_t *dst = g_video_buffer.data() + y * width;

            if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
                const auto *px = reinterpret_cast<const uint32_t *>(row);
                for (unsigned x = 0; x < width; ++x) {
                    dst[x] = static_cast<int32_t>(0xFF000000u | (px[x] & 0x00FFFFFFu));
                }
            } else if (g_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
                const auto *px = reinterpret_cast<const uint16_t *>(row);
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t c = px[x];
                    uint32_t r = ((c >> 11) & 0x1F) * 255 / 31;
                    uint32_t g = ((c >> 5) & 0x3F) * 255 / 63;
                    uint32_t b = (c & 0x1F) * 255 / 31;
                    dst[x] = static_cast<int32_t>(0xFF000000u | (r << 16) | (g << 8) | b);
                }
            } else {
                const auto *px = reinterpret_cast<const uint16_t *>(row);
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t c = px[x];
                    uint32_t r = ((c >> 10) & 0x1F) * 255 / 31;
                    uint32_t g = ((c >> 5) & 0x1F) * 255 / 31;
                    uint32_t b = (c & 0x1F) * 255 / 31;
                    dst[x] = static_cast<int32_t>(0xFF000000u | (r << 16) | (g << 8) | b);
                }
            }
        }
    }

    void audio_sample_callback(int16_t left, int16_t right) {
        std::lock_guard<std::mutex> lock(g_av_mutex);
        g_audio_buffer.push_back(left);
        g_audio_buffer.push_back(right);
    }

    size_t audio_sample_batch_callback(const int16_t *data, size_t frames) {
        if (!data || frames == 0) return 0;
        std::lock_guard<std::mutex> lock(g_av_mutex);
        g_audio_buffer.insert(g_audio_buffer.end(), data, data + frames * 2);
        return frames;
    }

    void input_poll_callback() {}

    int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
        if (port != 0) return 0;
        if (device == RETRO_DEVICE_JOYPAD) {
            if (id >= 16) return 0;
            return (g_input_mask & (1u << id)) ? 1 : 0;
        }
        if (device == RETRO_DEVICE_POINTER && index == 0) {
            switch (id) {
                case RETRO_DEVICE_ID_POINTER_X: return g_pointer_x;
                case RETRO_DEVICE_ID_POINTER_Y: return g_pointer_y;
                case RETRO_DEVICE_ID_POINTER_PRESSED: return g_pointer_pressed ? 1 : 0;
                default: return 0;
            }
        }
        return 0;
    }

    void close_core() {
        if (core_unload_game) core_unload_game();
        if (core_deinit) core_deinit();
        if (g_core_handle) {
            dlclose(g_core_handle);
            g_core_handle = nullptr;
        }
        core_init = nullptr;
        core_deinit = nullptr;
        core_load_game = nullptr;
        core_unload_game = nullptr;
        core_run = nullptr;
        core_get_system_av_info = nullptr;
        core_serialize_size = nullptr;
        core_serialize = nullptr;
        core_unserialize = nullptr;
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeLoadCore(
        JNIEnv *env, jobject, jstring corePath, jstring systemDir, jstring saveDir) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    close_core();

    const char *c_core = env->GetStringUTFChars(corePath, nullptr);
    const char *c_sys = env->GetStringUTFChars(systemDir, nullptr);
    const char *c_save = env->GetStringUTFChars(saveDir, nullptr);

    g_system_dir = c_sys ? c_sys : "";
    g_save_dir = c_save ? c_save : "";

    g_core_handle = dlopen(c_core, RTLD_LAZY);
    env->ReleaseStringUTFChars(corePath, c_core);
    env->ReleaseStringUTFChars(systemDir, c_sys);
    env->ReleaseStringUTFChars(saveDir, c_save);

    if (!g_core_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }

    core_init = reinterpret_cast<void (*)()>(dlsym(g_core_handle, "retro_init"));
    core_deinit = reinterpret_cast<void (*)()>(dlsym(g_core_handle, "retro_deinit"));
    core_set_environment = reinterpret_cast<void (*)(retro_environment_t)>(dlsym(g_core_handle, "retro_set_environment"));
    core_set_video_refresh = reinterpret_cast<void (*)(retro_video_refresh_t)>(dlsym(g_core_handle, "retro_set_video_refresh"));
    core_set_audio_sample = reinterpret_cast<void (*)(retro_audio_sample_t)>(dlsym(g_core_handle, "retro_set_audio_sample"));
    core_set_audio_sample_batch = reinterpret_cast<void (*)(retro_audio_sample_batch_t)>(dlsym(g_core_handle, "retro_set_audio_sample_batch"));
    core_set_input_poll = reinterpret_cast<void (*)(retro_input_poll_t)>(dlsym(g_core_handle, "retro_set_input_poll"));
    core_set_input_state = reinterpret_cast<void (*)(retro_input_state_t)>(dlsym(g_core_handle, "retro_set_input_state"));
    core_load_game = reinterpret_cast<bool (*)(const struct retro_game_info *)>(dlsym(g_core_handle, "retro_load_game"));
    core_unload_game = reinterpret_cast<void (*)()>(dlsym(g_core_handle, "retro_unload_game"));
    core_run = reinterpret_cast<void (*)()>(dlsym(g_core_handle, "retro_run"));
    core_get_system_av_info = reinterpret_cast<void (*)(struct retro_system_av_info *)>(dlsym(g_core_handle, "retro_get_system_av_info"));
    core_serialize_size = reinterpret_cast<size_t (*)()>(dlsym(g_core_handle, "retro_serialize_size"));
    core_serialize = reinterpret_cast<bool (*)(void *, size_t)>(dlsym(g_core_handle, "retro_serialize"));
    core_unserialize = reinterpret_cast<bool (*)(const void *, size_t)>(dlsym(g_core_handle, "retro_unserialize"));

    if (!core_init || !core_load_game || !core_run) {
        LOGE("Missing required libretro symbols");
        close_core();
        return JNI_FALSE;
    }

    core_set_environment(env_callback);
    core_set_video_refresh(video_refresh_callback);
    core_set_audio_sample(audio_sample_callback);
    core_set_audio_sample_batch(audio_sample_batch_callback);
    core_set_input_poll(input_poll_callback);
    core_set_input_state(input_state_callback);

    core_init();
    LOGI("Core initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeLoadRom(
        JNIEnv *env, jobject, jstring romPath) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!core_load_game) return JNI_FALSE;
    const char *c_rom = env->GetStringUTFChars(romPath, nullptr);
    struct retro_game_info info{};
    info.path = c_rom;
    bool ok = core_load_game(&info);
    env->ReleaseStringUTFChars(romPath, c_rom);

    if (ok && core_get_system_av_info) {
        struct retro_system_av_info av{};
        core_get_system_av_info(&av);
        if (av.timing.sample_rate > 0.0) {
            g_sample_rate = av.timing.sample_rate;
        }
        g_video_width = av.geometry.base_width;
        g_video_height = av.geometry.base_height;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeRunFrame(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (core_run) core_run();
}

JNIEXPORT void JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeSetInput(JNIEnv *, jobject, jint mask) {
    g_input_mask = static_cast<uint32_t>(mask);
}

JNIEXPORT void JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeSetTouch(
        JNIEnv *, jobject, jint x, jint y, jboolean pressed) {
    g_pointer_x = static_cast<int16_t>(x);
    g_pointer_y = static_cast<int16_t>(y);
    g_pointer_pressed = (pressed == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeSaveState(
        JNIEnv *env, jobject, jstring statePath) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!core_serialize_size || !core_serialize) return JNI_FALSE;
    size_t size = core_serialize_size();
    if (size == 0) return JNI_FALSE;

    std::vector<uint8_t> buf(size);
    if (!core_serialize(buf.data(), size)) return JNI_FALSE;

    const char *c_path = env->GetStringUTFChars(statePath, nullptr);
    std::ofstream out(c_path, std::ios::binary);
    env->ReleaseStringUTFChars(statePath, c_path);
    if (!out) return JNI_FALSE;

    out.write(reinterpret_cast<const char *>(buf.data()), static_cast<std::streamsize>(size));
    return out.good() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeLoadState(
        JNIEnv *env, jobject, jstring statePath) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!core_unserialize) return JNI_FALSE;

    const char *c_path = env->GetStringUTFChars(statePath, nullptr);
    std::ifstream in(c_path, std::ios::binary | std::ios::ate);
    env->ReleaseStringUTFChars(statePath, c_path);
    if (!in) return JNI_FALSE;

    std::streamsize size = in.tellg();
    if (size <= 0) return JNI_FALSE;
    in.seekg(0, std::ios::beg);

    std::vector<uint8_t> buf(static_cast<size_t>(size));
    if (!in.read(reinterpret_cast<char *>(buf.data()), size)) return JNI_FALSE;

    return core_unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeGetVideoWidth(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_av_mutex);
    return static_cast<jint>(g_video_width);
}

JNIEXPORT jint JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeGetVideoHeight(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_av_mutex);
    return static_cast<jint>(g_video_height);
}

JNIEXPORT jint JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeGetSampleRate(JNIEnv *, jobject) {
    return static_cast<jint>(g_sample_rate);
}

JNIEXPORT jintArray JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeConsumeVideoFrame(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_av_mutex);
    if (g_video_buffer.empty()) return nullptr;
    jintArray arr = env->NewIntArray(static_cast<jsize>(g_video_buffer.size()));
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(g_video_buffer.size()), g_video_buffer.data());
    return arr;
}

JNIEXPORT jshortArray JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeConsumeAudioSamples(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_av_mutex);
    if (g_audio_buffer.empty()) return nullptr;
    jshortArray arr = env->NewShortArray(static_cast<jsize>(g_audio_buffer.size()));
    env->SetShortArrayRegion(arr, 0, static_cast<jsize>(g_audio_buffer.size()), g_audio_buffer.data());
    g_audio_buffer.clear();
    return arr;
}

JNIEXPORT void JNICALL
Java_com_retrohandheld_emulator_LibretroBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    close_core();
}

}
