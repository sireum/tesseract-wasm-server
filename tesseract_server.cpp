// Tesseract OCR WASM Server
//
// English traineddata is embedded at compile time (eng_traineddata.h).
//
// Protocol (binary, big-endian, over stdin/stdout):
//   Query loop:
//     Request:
//       [4-byte image_len][image bytes (PNG/BMP/etc.)]
//       image_len == 0 means shutdown.
//     Response:
//       [4-byte result_len][UTF-8 text with bounding boxes]
//
// Output format (one line per detected word):
//   x1 y1 x2 y2 confidence text
//
// Where (x1,y1) is top-left and (x2,y2) is bottom-right of the bounding box,
// confidence is 0-100, and text is the recognized word.

#include <tesseract/baseapi.h>
#include <leptonica/allheaders.h>

#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sstream>
#include <vector>

#include "eng_traineddata.h"

static uint32_t read_u32() {
    uint8_t buf[4];
    if (fread(buf, 1, 4, stdin) != 4) return 0;
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
         | ((uint32_t)buf[2] << 8)  | (uint32_t)buf[3];
}

static void write_u32(uint32_t v) {
    uint8_t buf[4];
    buf[0] = (uint8_t)(v >> 24);
    buf[1] = (uint8_t)(v >> 16);
    buf[2] = (uint8_t)(v >> 8);
    buf[3] = (uint8_t)(v);
    fwrite(buf, 1, 4, stdout);
}

static bool read_exact(void *dst, size_t n) {
    size_t total = 0;
    while (total < n) {
        size_t r = fread((char *)dst + total, 1, n - total, stdin);
        if (r == 0) return false;
        total += r;
    }
    return true;
}

int main() {
    // Initialize Tesseract from embedded traineddata.
    tesseract::TessBaseAPI api;
    if (api.Init((const char *)tessdata_eng_traineddata,
                 (int)tessdata_eng_traineddata_len, "eng",
                 tesseract::OEM_LSTM_ONLY, nullptr, 0,
                 nullptr, nullptr, false, nullptr) != 0) {
        fprintf(stderr, "Failed to initialize Tesseract\n");
        return 1;
    }

    // Signal ready
    fflush(stderr);

    while (true) {
        // Read image length
        uint32_t img_len = read_u32();
        if (img_len == 0) break;

        // Read image data
        std::vector<uint8_t> img_data(img_len);
        if (!read_exact(img_data.data(), img_len)) break;

        // Decode image using Leptonica
        Pix *pix = pixReadMem(img_data.data(), img_len);
        if (!pix) {
            std::string err = "ERROR: Failed to decode image";
            write_u32((uint32_t)err.size());
            fwrite(err.data(), 1, err.size(), stdout);
            fflush(stdout);
            continue;
        }

        // Run OCR
        api.SetImage(pix);
        api.Recognize(nullptr);

        // Extract word-level bounding boxes and text
        std::ostringstream out;
        tesseract::ResultIterator *ri = api.GetIterator();
        if (ri) {
            do {
                const char *word = ri->GetUTF8Text(tesseract::RIL_WORD);
                if (!word) continue;

                float conf = ri->Confidence(tesseract::RIL_WORD);
                int x1, y1, x2, y2;
                ri->BoundingBox(tesseract::RIL_WORD, &x1, &y1, &x2, &y2);

                out << x1 << " " << y1 << " " << x2 << " " << y2
                    << " " << (int)conf << " " << word << "\n";

                delete[] word;
            } while (ri->Next(tesseract::RIL_WORD));
        }

        pixDestroy(&pix);
        api.Clear();

        // Write response
        std::string result = out.str();
        write_u32((uint32_t)result.size());
        if (!result.empty()) {
            fwrite(result.data(), 1, result.size(), stdout);
        }
        fflush(stdout);
    }

    api.End();
    return 0;
}
