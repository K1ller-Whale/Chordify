# The engine's native library, libchordify_engine.so: NNLS Chroma run by a minimal Vamp host,
# and libsoxr. The third-party code is unmodified upstream source at the versions the Python
# reference uses (third_party/README.md); flags follow how that reference is built.
cmake_minimum_required(VERSION 3.22.1)
project(chordify_engine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# No fused multiply-add anywhere: the Python reference runs on x86-64 without FMA, and arm64
# would otherwise contract a*b+c and round differently. -O3 even in debug builds, so the
# instrumented tests and the benchmark run the code that ships.
set(PARITY_FLAGS -O3 -ffp-contract=off)

# libsoxr, configured as python-soxr 1.1.0's CMakeLists.txt configures it.
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WITH_OPENMP OFF CACHE BOOL "" FORCE)
set(WITH_LSR_BINDINGS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/libsoxr ${CMAKE_CURRENT_BINARY_DIR}/libsoxr EXCLUDE_FROM_ALL)
target_compile_options(soxr PRIVATE ${PARITY_FLAGS} -w)

# The host side of Vamp plugin SDK 2.6, as the Python vamp 1.1.0 package bundles it: the
# input-domain adapter (Hann window, built-in FFT) that turns audio into NNLS Chroma's input.
set(VAMP_SDK ${THIRD_PARTY}/vamp-plugin-sdk)
add_library(vamp_hostsdk STATIC
    ${VAMP_SDK}/src/vamp-hostsdk/PluginInputDomainAdapter.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/PluginWrapper.cpp
    ${VAMP_SDK}/src/vamp-hostsdk/RealTime.cpp)
target_include_directories(vamp_hostsdk PUBLIC ${VAMP_SDK})
target_compile_definitions(vamp_hostsdk PRIVATE _USE_MATH_DEFINES)
target_compile_options(vamp_hostsdk PRIVATE ${PARITY_FLAGS} -w)

# NNLS Chroma, compiled into the host's namespace so the adapter wraps it directly, with the
# optimisation flags of its Makefile.linux (how tools/install_nnls_chroma.sh builds it).
# -ffast-math comes before PARITY_FLAGS: it turns contraction back on, so -ffp-contract=off must
# follow it, and CMake drops a repeated option rather than keeping its last place.
set(NNLS ${THIRD_PARTY}/nnls-chroma)
add_library(nnls_chroma STATIC
    ${NNLS}/NNLSBase.cpp
    ${NNLS}/NNLSChroma.cpp
    ${NNLS}/chromamethods_nnls.cpp
    ${NNLS}/nnls.c
    nnls_factory.cpp)
target_include_directories(nnls_chroma PRIVATE ${NNLS} ${VAMP_SDK})
target_compile_definitions(nnls_chroma PRIVATE _VAMP_PLUGIN_IN_HOST_NAMESPACE)
target_compile_options(nnls_chroma PRIVATE -ftree-vectorize -ffast-math ${PARITY_FLAGS} -w)

add_library(chordify_engine SHARED nnls_host.cpp nnls_jni.cpp soxr_jni.cpp)
target_include_directories(chordify_engine PRIVATE ${THIRD_PARTY}/libsoxr/src)
target_compile_options(chordify_engine PRIVATE ${PARITY_FLAGS} -Wall)
target_link_libraries(chordify_engine PRIVATE nnls_chroma vamp_hostsdk soxr log)
