#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

get_prop() { grep "^$1=" "${SCRIPT_DIR}/versions.properties" | cut -d= -f2; }

TESSERACT_V=$(get_prop tesseract.version)
LEPTONICA_V=$(get_prop leptonica.version)
ZLIB_V=$(get_prop zlib.version)
LIBPNG_V=$(get_prop libpng.version)
NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

# Ensure Emscripten is available
if ! command -v emcc &>/dev/null; then
  echo "ERROR: emcc not found. Please install and activate Emscripten SDK."
  echo "  git clone https://github.com/emscripten-core/emsdk.git"
  echo "  cd emsdk && ./emsdk install latest && ./emsdk activate latest"
  echo "  source emsdk_env.sh"
  exit 1
fi

echo "=== Step 1a: Build zlib for WASM ==="
ZLIB_INSTALL="$SCRIPT_DIR/zlib-wasm-install"
if [ ! -d "$ZLIB_INSTALL" ]; then
  if [ ! -d zlib ]; then
    git clone --depth 1 --branch "v$ZLIB_V" https://github.com/madler/zlib.git
  fi
  cd zlib
  CFLAGS="-fwasm-exceptions -pthread" emconfigure ./configure \
    --prefix="$ZLIB_INSTALL" --static
  emmake make -j"$NPROC"
  emmake make install
  cd "$SCRIPT_DIR"
fi

echo "=== Step 1b: Build libpng for WASM ==="
PNG_INSTALL="$SCRIPT_DIR/libpng-wasm-install"
if [ ! -d "$PNG_INSTALL" ]; then
  if [ ! -d libpng ]; then
    git clone --depth 1 --branch "v$LIBPNG_V" https://github.com/pnggroup/libpng.git
  fi
  mkdir -p libpng/build-wasm
  cd libpng/build-wasm
  emcmake cmake .. \
    -DCMAKE_INSTALL_PREFIX="$PNG_INSTALL" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DPNG_TESTS=OFF \
    -DPNG_EXECUTABLES=OFF \
    -DZLIB_LIBRARY="$ZLIB_INSTALL/lib/libz.a" \
    -DZLIB_INCLUDE_DIR="$ZLIB_INSTALL/include" \
    -DCMAKE_C_FLAGS="-fwasm-exceptions -pthread"
  emmake make -j"$NPROC"
  emmake make install
  cd "$SCRIPT_DIR"
fi

echo "=== Step 1c: Build Leptonica for WASM ==="
if [ ! -d leptonica ]; then
  git clone --depth 1 --branch "$LEPTONICA_V" https://github.com/DanBloomberg/leptonica.git
fi

LEPT_INSTALL="$SCRIPT_DIR/leptonica-wasm-install"
if [ ! -d "$LEPT_INSTALL" ]; then
  mkdir -p leptonica/build-wasm
  cd leptonica/build-wasm
  emcmake cmake .. \
    -DCMAKE_INSTALL_PREFIX="$LEPT_INSTALL" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DSW_BUILD=OFF \
    -DBUILD_PROG=OFF \
    -DZLIB_LIBRARY="$ZLIB_INSTALL/lib/libz.a" \
    -DZLIB_INCLUDE_DIR="$ZLIB_INSTALL/include" \
    -DPNG_LIBRARY="$PNG_INSTALL/lib/libpng.a" \
    -DPNG_PNG_INCLUDE_DIR="$PNG_INSTALL/include" \
    -DCMAKE_C_FLAGS="-fwasm-exceptions -pthread" \
    -DCMAKE_CXX_FLAGS="-fwasm-exceptions -pthread"
  emmake make -j"$NPROC"
  emmake make install
  cd "$SCRIPT_DIR"
fi

echo "=== Step 2: Build Tesseract for WASM ==="
if [ ! -d tesseract ]; then
  git clone --depth 1 --branch "$TESSERACT_V" https://github.com/tesseract-ocr/tesseract.git
fi

TESS_INSTALL="$SCRIPT_DIR/tesseract-wasm-install"
if [ ! -d "$TESS_INSTALL" ]; then
  mkdir -p tesseract/build-wasm
  cd tesseract/build-wasm
  emcmake cmake .. \
    -DCMAKE_INSTALL_PREFIX="$TESS_INSTALL" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DDISABLED_LEGACY_ENGINE=ON \
    -DGRAPHICS_DISABLED=ON \
    -DLeptonica_DIR="$LEPT_INSTALL/lib/cmake/leptonica" \
    -DCMAKE_C_FLAGS="-fwasm-exceptions -pthread" \
    -DCMAKE_CXX_FLAGS="-fwasm-exceptions -pthread"
  emmake make -j"$NPROC"
  emmake make install
  cd "$SCRIPT_DIR"
fi

echo "=== Step 3: Download English tessdata ==="
mkdir -p tessdata
if [ ! -f tessdata/eng.traineddata ]; then
  curl -L -o tessdata/eng.traineddata \
    "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
fi

echo "=== Step 4: Generate embedded traineddata header ==="
xxd -i tessdata/eng.traineddata > eng_traineddata.h

echo "=== Step 5: Compile tesseract_server.cpp ==="
em++ -O2 -std=c++17 -fwasm-exceptions \
  tesseract_server.cpp \
  -I"$TESS_INSTALL/include" \
  -I"$LEPT_INSTALL/include" \
  -L"$TESS_INSTALL/lib" \
  -L"$LEPT_INSTALL/lib" \
  -L"$PNG_INSTALL/lib" \
  -L"$ZLIB_INSTALL/lib" \
  -ltesseract -lleptonica -lpng -lz \
  -s STANDALONE_WASM \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s INITIAL_MEMORY=67108864 \
  -o tesseract_server.wasm

echo "=== Step 6: Build env_stub.wasm ==="
wat2wasm --enable-all env_stub.wat -o env_stub.wasm

echo "=== Step 7: GraalWasm variant (strip EH) ==="
wasm-opt --strip-eh --all-features -O2 \
  tesseract_server.wasm -o tesseract_server_noeh.wasm
wasm-merge --all-features \
  tesseract_server_noeh.wasm tesseract \
  env_stub.wasm env \
  -o tesseract_server_graal.wasm

echo "=== Build complete ==="
ls -lh tesseract_server_graal.wasm
