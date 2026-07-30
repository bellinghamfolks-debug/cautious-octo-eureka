// JNI bridge to a quantized vision-language model running through llama.cpp + libmtmd.
//
// Written against the llama.cpp tag pinned in CMakeLists.txt. The multimodal API
// (libmtmd) is the least stable surface in that project; if the tag moves, this
// file is the one to re-check.
//
// Design notes that matter for an assistive app:
//
//  * Token pieces are emitted to Kotlin only on UTF-8 character boundaries. A
//    Qwen tokenizer routinely splits a single Arabic code point across two
//    tokens, and forwarding a half sequence produces replacement characters
//    that a screen reader announces as garbage.
//  * Generation is cancellable from Kotlin between tokens, so a stalled or
//    looping model never holds the capture pipeline.
//  * Exactly one image is in flight per context. Batching images on a phone is
//    what pushes a 3B VLM past the memory ceiling.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "VisionBridgeVLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct VlmSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *mctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::mutex inference_mutex;
    std::atomic<bool> cancelled{false};

    ~VlmSession() {
        if (mctx) mtmd_free(mctx);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        llama_log_set(
            [](ggml_log_level level, const char *text, void *) {
                if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
            },
            nullptr);
    });
}

/** Builds a java.lang.String from real UTF-8 bytes, not JNI's modified UTF-8. */
jstring utf8_to_jstring(JNIEnv *env, const std::string &value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte *>(value.data()));

    jclass string_class = env->FindClass("java/lang/String");
    jclass charset_class = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8_field = env->GetStaticFieldID(charset_class, "UTF_8", "Ljava/nio/charset/Charset;");
    jobject utf8 = env->GetStaticObjectField(charset_class, utf8_field);
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/nio/charset/Charset;)V");

    auto result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, utf8));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(charset_class);
    return result;
}

std::string jstring_to_utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

/**
 * Number of trailing bytes that form an incomplete UTF-8 sequence, so they can
 * be held back until the next token completes the character.
 */
size_t incomplete_utf8_tail(const std::string &value) {
    const size_t size = value.size();
    for (size_t back = 1; back <= 4 && back <= size; ++back) {
        const auto byte = static_cast<unsigned char>(value[size - back]);
        if ((byte & 0xC0) == 0x80) continue;  // continuation byte, keep walking
        size_t expected = 1;
        if ((byte & 0xE0) == 0xC0) expected = 2;
        else if ((byte & 0xF0) == 0xE0) expected = 3;
        else if ((byte & 0xF8) == 0xF0) expected = 4;
        return back < expected ? back : 0;
    }
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_abdullah_visionbridge_data_localvlm_LocalVlmEngine_nativeLoad(
    JNIEnv *env, jobject, jstring model_path, jstring mmproj_path, jint n_threads,
    jint n_ctx, jint n_gpu_layers) {
    ensure_backend();

    const std::string model_file = jstring_to_utf8(env, model_path);
    const std::string mmproj_file = jstring_to_utf8(env, mmproj_path);

    auto session = std::make_unique<VlmSession>();

    llama_model_params model_params = llama_model_default_params();
    // Weights are mmap'd so the kernel can evict clean pages under pressure
    // instead of the process being killed. This is the single most important
    // setting for surviving a 2 GB model on a phone.
    model_params.use_mmap = true;
    model_params.use_mlock = false;
    model_params.n_gpu_layers = n_gpu_layers;

    session->model = llama_model_load_from_file(model_file.c_str(), model_params);
    if (!session->model) {
        LOGE("failed to load model weights from %s", model_file.c_str());
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(n_ctx);
    ctx_params.n_batch = static_cast<uint32_t>(n_ctx);
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    // A single image plus one page of text never needs a growing KV cache, and
    // f16 halves its footprint against the default.
    ctx_params.type_k = GGML_TYPE_F16;
    ctx_params.type_v = GGML_TYPE_F16;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (!session->ctx) {
        LOGE("failed to create llama context");
        return 0;
    }
    session->n_ctx = n_ctx;
    session->n_batch = n_ctx;

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = n_gpu_layers > 0;
    mparams.n_threads = n_threads;
    mparams.print_timings = false;

    session->mctx = mtmd_init_from_file(mmproj_file.c_str(), session->model, mparams);
    if (!session->mctx) {
        LOGE("failed to load vision projector from %s", mmproj_file.c_str());
        return 0;
    }

    LOGI("VLM loaded: ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT void JNICALL
Java_com_abdullah_visionbridge_data_localvlm_LocalVlmEngine_nativeFree(
    JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<VlmSession *>(handle);
    if (!session) return;
    session->cancelled.store(true);
    std::lock_guard<std::mutex> guard(session->inference_mutex);
    delete session;
}

JNIEXPORT void JNICALL
Java_com_abdullah_visionbridge_data_localvlm_LocalVlmEngine_nativeCancel(
    JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<VlmSession *>(handle);
    if (session) session->cancelled.store(true);
}

/**
 * Runs one image + prompt to completion.
 *
 * rgb is tightly packed 8-bit RGB, three bytes per pixel, which is what
 * mtmd_bitmap_init expects. The listener receives UTF-8-safe fragments and
 * returns false to stop generation early; the Kotlin loop guard uses that to
 * cut a degenerate repetition the moment it is detected.
 */
JNIEXPORT jstring JNICALL
Java_com_abdullah_visionbridge_data_localvlm_LocalVlmEngine_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jbyteArray rgb, jint width, jint height,
    jstring prompt, jint max_tokens, jfloat temperature, jfloat top_p,
    jfloat repeat_penalty, jint repeat_last_n, jint seed, jobject listener) {
    auto *session = reinterpret_cast<VlmSession *>(handle);
    if (!session) return utf8_to_jstring(env, "");

    std::lock_guard<std::mutex> guard(session->inference_mutex);
    session->cancelled.store(false);

    jclass listener_class = env->GetObjectClass(listener);
    jmethodID on_token = env->GetMethodID(listener_class, "onToken", "(Ljava/lang/String;)Z");

    const std::string prompt_text = jstring_to_utf8(env, prompt);

    jbyte *rgb_bytes = env->GetByteArrayElements(rgb, nullptr);
    const jsize rgb_size = env->GetArrayLength(rgb);
    if (!rgb_bytes || rgb_size != static_cast<jsize>(width) * height * 3) {
        if (rgb_bytes) env->ReleaseByteArrayElements(rgb, rgb_bytes, JNI_ABORT);
        LOGE("rgb buffer size mismatch");
        return utf8_to_jstring(env, "");
    }

    mtmd_bitmap *bitmap = mtmd_bitmap_init(
        static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        reinterpret_cast<const unsigned char *>(rgb_bytes));
    env->ReleaseByteArrayElements(rgb, rgb_bytes, JNI_ABORT);
    if (!bitmap) return utf8_to_jstring(env, "");

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text input_text{};
    input_text.text = prompt_text.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    const mtmd_bitmap *bitmaps[1] = {bitmap};
    const int32_t tokenized = mtmd_tokenize(session->mctx, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (tokenized != 0) {
        mtmd_input_chunks_free(chunks);
        LOGE("mtmd_tokenize failed: %d", tokenized);
        return utf8_to_jstring(env, "");
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);

    llama_pos n_past = 0;
    const int32_t evaluated = mtmd_helper_eval_chunks(
        session->mctx, session->ctx, chunks, /*n_past=*/0, /*seq_id=*/0,
        session->n_batch, /*logits_last=*/true, &n_past);
    mtmd_input_chunks_free(chunks);
    if (evaluated != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", evaluated);
        return utf8_to_jstring(env, "");
    }

    // Sampler chain. The repetition penalty is the first line of defence
    // against the fragment-looping failure mode; the Kotlin loop guard is the
    // second, because penalties alone do not stop a model that repeats a whole
    // line with different spacing.
    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(
        sampler, llama_sampler_init_penalties(repeat_last_n, repeat_penalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    std::string full_text;
    std::string pending;  // holds an incomplete UTF-8 sequence between tokens
    llama_batch batch = llama_batch_init(1, 0, 1);

    for (int generated = 0; generated < max_tokens; ++generated) {
        if (session->cancelled.load()) break;

        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        llama_sampler_accept(sampler, token);

        char piece[256];
        const int piece_length = llama_token_to_piece(
            session->vocab, token, piece, sizeof(piece), 0, /*special=*/false);
        if (piece_length > 0) {
            pending.append(piece, piece_length);
            const size_t tail = incomplete_utf8_tail(pending);
            const std::string emit = pending.substr(0, pending.size() - tail);
            pending = pending.substr(pending.size() - tail);

            if (!emit.empty()) {
                full_text += emit;
                jstring fragment = utf8_to_jstring(env, emit);
                const jboolean keep_going = env->CallBooleanMethod(listener, on_token, fragment);
                env->DeleteLocalRef(fragment);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
                if (keep_going == JNI_FALSE) break;
            }
        }

        if (n_past >= session->n_ctx - 4) break;  // never overrun the context

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(session->ctx, batch) != 0) break;
        n_past++;
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    // Release the KV cache immediately; the next frame re-evaluates from zero
    // anyway, and holding it doubles idle memory between captures.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    return utf8_to_jstring(env, full_text);
}

}  // extern "C"
