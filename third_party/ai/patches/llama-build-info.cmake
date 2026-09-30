# Applied only to the build-directory source copy. Exact blocks fail on pin drift.
function(motd_replace_once path expected replacement)
    file(READ "${path}" contents)
    string(FIND "${contents}" "${expected}" first)
    if(first EQUAL -1)
        message(FATAL_ERROR "Pinned llama build-info block missing: ${path}")
    endif()
    string(LENGTH "${expected}" length)
    math(EXPR after "${first} + ${length}")
    string(SUBSTRING "${contents}" ${after} -1 tail)
    string(FIND "${tail}" "${expected}" duplicate)
    if(NOT duplicate EQUAL -1)
        message(FATAL_ERROR "Duplicate pinned llama build-info block: ${path}")
    endif()
    string(REPLACE "${expected}" "${replacement}" contents "${contents}")
    file(WRITE "${path}" "${contents}")
endfunction()
set(expected [=[set(BUILD_NUMBER 0)
set(BUILD_COMMIT "unknown")
set(BUILD_COMPILER "unknown")
set(BUILD_TARGET "unknown")

# Look for git
find_package(Git)
if(NOT Git_FOUND)
    find_program(GIT_EXECUTABLE NAMES git git.exe)
    if(GIT_EXECUTABLE)
        set(Git_FOUND TRUE)
        message(STATUS "Found Git: ${GIT_EXECUTABLE}")
    else()
        message(WARNING "Git not found. Build info will not be accurate.")
    endif()
endif()

# Get the commit count and hash
if(Git_FOUND)
    execute_process(
        COMMAND ${GIT_EXECUTABLE} rev-parse --short HEAD
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
        OUTPUT_VARIABLE HEAD
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE RES
    )
    if (RES EQUAL 0)
        set(BUILD_COMMIT ${HEAD})
    endif()
    execute_process(
        COMMAND ${GIT_EXECUTABLE} rev-list --count HEAD
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
        OUTPUT_VARIABLE COUNT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE RES
    )
    if (RES EQUAL 0)
        set(BUILD_NUMBER ${COUNT})
    endif()
endif()

set(BUILD_COMPILER "${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION}")

if(CMAKE_VS_PLATFORM_NAME)
    set(BUILD_TARGET ${CMAKE_VS_PLATFORM_NAME})
else()
    set(BUILD_TARGET "${CMAKE_SYSTEM_NAME} ${CMAKE_SYSTEM_PROCESSOR}")
endif()
]=])
set(replacement "set(BUILD_NUMBER 0)\nset(BUILD_COMMIT \"${MOTD_LLAMA_COMMIT}\")\nset(BUILD_COMPILER \"\${CMAKE_C_COMPILER_ID} \${CMAKE_C_COMPILER_VERSION}\")\nset(BUILD_TARGET \"\${CMAKE_SYSTEM_NAME} \${ANDROID_ABI}\")\n")
if(MOTD_HOST_SMOKE)
    set(replacement "set(BUILD_NUMBER 0)\nset(BUILD_COMMIT \"${MOTD_LLAMA_COMMIT}\")\nset(BUILD_COMPILER \"\${CMAKE_C_COMPILER_ID} \${CMAKE_C_COMPILER_VERSION}\")\nset(BUILD_TARGET \"\${CMAKE_SYSTEM_NAME} portable\")\n")
endif()
motd_replace_once("${MOTD_LLAMA_SOURCE}/cmake/build-info.cmake" "${expected}" "${replacement}")
set(expected [=[find_program(GIT_EXE NAMES git git.exe NO_CMAKE_FIND_ROOT_PATH)
if(GIT_EXE)
    # Get current git commit hash
    execute_process(COMMAND ${GIT_EXE} rev-parse --short HEAD
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
        OUTPUT_VARIABLE GGML_BUILD_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )

    # Check if the working directory is dirty (i.e., has uncommitted changes)
    execute_process(COMMAND ${GIT_EXE} diff-index --quiet HEAD -- .
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
        RESULT_VARIABLE GGML_GIT_DIRTY
        ERROR_QUIET
    )
endif()

set(GGML_VERSION "${GGML_VERSION_BASE}")

if(NOT GGML_BUILD_COMMIT)
    set(GGML_BUILD_COMMIT "unknown")
endif()

# Build the commit string with optional dirty flag
if(DEFINED GGML_GIT_DIRTY AND GGML_GIT_DIRTY EQUAL 1)
    set(GGML_BUILD_COMMIT "${GGML_BUILD_COMMIT}-dirty")
endif()]=])
motd_replace_once("${MOTD_LLAMA_SOURCE}/ggml/CMakeLists.txt" "${expected}" "set(GGML_BUILD_COMMIT \"${MOTD_LLAMA_COMMIT}\")\nset(GGML_GIT_DIRTY 0)\nset(GGML_VERSION \"\${GGML_VERSION_BASE}\")")
