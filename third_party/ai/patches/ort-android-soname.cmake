# Android packages exactly libonnxruntime.so, not the Unix .so.1 symlink chain.
# Defer until upstream has set its target properties; never modify checked sources.
if(PROJECT_NAME STREQUAL "onnxruntime" AND ANDROID)
    function(motd_android_ort_soname)
        set_target_properties(onnxruntime PROPERTIES VERSION "" SOVERSION "")
    endfunction()
    cmake_language(DEFER CALL motd_android_ort_soname)
endif()
