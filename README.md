# Tesseract WASM Server

A standalone [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) engine
compiled to WebAssembly, designed to run as a headless server communicating over
stdin/stdout via a binary protocol.  English trained data is embedded at compile
time, so no external files are needed at runtime.

## Protocol

Binary, big-endian, over stdin/stdout:

**Request:** `[4-byte image_len][image bytes (PNG/BMP/etc.)]`
- `image_len == 0` signals shutdown.

**Response:** `[4-byte result_len][UTF-8 text]`
- One line per detected word: `x1 y1 x2 y2 confidence text`
- `(x1,y1)` is top-left, `(x2,y2)` is bottom-right of the bounding box.
- `confidence` is 0-100.

## Requirements

**Building:** [Emscripten SDK](https://emscripten.org),
[Binaryen](https://github.com/WebAssembly/binaryen) (`wasm-opt`, `wasm-merge`),
[WABT](https://github.com/WebAssembly/wabt) (`wat2wasm`)

**Running:** [wasmtime](https://wasmtime.dev) or
[GraalWasm](https://www.graalvm.org/latest/reference-manual/wasm/) (via GraalVM Polyglot)

## Building

```bash
./build.sh
```

This will:
1. Build zlib, libpng, Leptonica, and Tesseract for WASM using Emscripten
2. Download and embed English trained data
3. Compile `tesseract_server.cpp` to `tesseract_server.wasm`
4. Build `env_stub.wasm` (stubs for unused syscalls)
5. Produce `tesseract_server_graal.wasm` (GraalWasm-compatible variant with
   exception handling stripped and env stubs merged)

## Running

With wasmtime:
```bash
wasmtime run tesseract_server_graal.wasm
```

With GraalWasm (in-process):
```bash
scala-cli run test_server.sc -- tesseract_server_graal.wasm --graalvm
```

## Testing

```bash
scala-cli run test_server.sc -- tesseract_server_graal.wasm
scala-cli run test_server.sc -- tesseract_server_graal.wasm --graalvm
```

Tests render text onto images, send them to the server, and verify OCR results
including bounding box format and sequential query reuse.

## Versions

Dependency versions are managed in `versions.properties`:

| Property | Description |
|---|---|
| `tesseract.version` | Tesseract OCR version |
| `leptonica.version` | Leptonica image library version |
| `zlib.version` | zlib compression library version |
| `libpng.version` | libpng version |

## Releases

The GitHub Actions [workflow](.github/workflows/build.yml) builds and uploads
`tesseract-server-<version>.wasm` (along with license files) to the
[sireum/rolling](https://github.com/sireum/rolling) releases on every push.

## License

The wrapper code (`tesseract_server.cpp`, `test_server.sc`, `build.sh`) is under
the [BSD 2-Clause License](license.txt).  Tesseract and
Leptonica are under their respective licenses (uploaded alongside the WASM binary).
