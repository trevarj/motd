if(NOT DEFINED MOTD_WHISPER_PATCH_SOURCE OR NOT EXISTS "${MOTD_WHISPER_PATCH_SOURCE}")
    message(FATAL_ERROR "Whisper patch source is missing: ${MOTD_WHISPER_PATCH_SOURCE}")
endif()
file(SHA256 "${MOTD_WHISPER_PATCH_SOURCE}" MOTD_WHISPER_SOURCE_SHA256)
if(NOT MOTD_WHISPER_SOURCE_SHA256 STREQUAL "8e3c277ba36eb42a9882e3f0749b54521571a63340852a8679f6f8d64384d09d")
    message(FATAL_ERROR "Pinned whisper.cpp source does not match the allocation patch input")
endif()
unset(MOTD_WHISPER_SOURCE_SHA256)

function(motd_replace_whisper_source description expected replacement)
    file(READ "${MOTD_WHISPER_PATCH_SOURCE}" source)
    string(FIND "${source}" "${expected}" first)
    if(first EQUAL -1)
        message(FATAL_ERROR "Pinned whisper.cpp context changed while applying ${description}")
    endif()

    string(LENGTH "${expected}" expected_length)
    math(EXPR suffix_start "${first} + ${expected_length}")
    string(SUBSTRING "${source}" 0 ${first} prefix)
    string(SUBSTRING "${source}" ${suffix_start} -1 suffix)
    file(WRITE "${MOTD_WHISPER_PATCH_SOURCE}" "${prefix}${replacement}${suffix}")
endfunction()

motd_replace_whisper_source(
    "the allocation-status seam"
    [=[static whisper_global g_state;

template<typename T>]=]
    [=[static whisper_global g_state;
static thread_local bool g_model_load_allocation_failed = false;

extern "C" bool motd_whisper_model_load_allocation_failed() noexcept {
    return g_model_load_allocation_failed;
}

template<typename T>]=])

motd_replace_whisper_source(
    "the model tensor allocation guard"
    [=[        ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);
        if (buf) {
            model.buffers.emplace_back(buf);

            size_t size_main = ggml_backend_buffer_get_size(buf);
            WHISPER_LOG_INFO("%s: %12s total size = %8.2f MB\n", __func__, ggml_backend_buffer_name(buf), size_main / 1e6);
        }]=]
    [=[        ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);
        if (!buf) {
            g_model_load_allocation_failed = true;
            WHISPER_LOG_ERROR("%s: failed to allocate model tensor buffer\n", __func__);
            return false;
        }

        model.buffers.emplace_back(buf);

        size_t size_main = ggml_backend_buffer_get_size(buf);
        WHISPER_LOG_INFO("%s: %12s total size = %8.2f MB\n", __func__, ggml_backend_buffer_name(buf), size_main / 1e6);]=])

motd_replace_whisper_source(
    "the allocation-status reset"
    [=[struct whisper_context * whisper_init_with_params_no_state(struct whisper_model_loader * loader, struct whisper_context_params params) {
    ggml_time_init();]=]
    [=[struct whisper_context * whisper_init_with_params_no_state(struct whisper_model_loader * loader, struct whisper_context_params params) {
    g_model_load_allocation_failed = false;
    ggml_time_init();]=])
