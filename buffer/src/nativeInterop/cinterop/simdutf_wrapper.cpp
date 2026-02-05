// simdutf C wrapper implementation
// This file provides C-linkage functions that wrap simdutf's C++ API

#include "simdutf.h"
#include <stddef.h>
#include <stdint.h>

extern "C" {

int buf_simdutf_validate_utf8(const char* buf, size_t len) {
    return simdutf::validate_utf8(buf, len) ? 1 : 0;
}

size_t buf_simdutf_convert_utf8_to_utf16le(
    const char* input,
    size_t length,
    uint16_t* output
) {
    return simdutf::convert_utf8_to_utf16le(input, length, reinterpret_cast<char16_t*>(output));
}

size_t buf_simdutf_utf16_length_from_utf8(const char* input, size_t length) {
    return simdutf::utf16_length_from_utf8(input, length);
}

size_t buf_simdutf_convert_utf8_to_chararray(
    const char* input,
    size_t length,
    char16_t* output
) {
    return simdutf::convert_utf8_to_utf16le(input, length, output);
}

size_t buf_simdutf_utf8_find_boundary(const char* buffer, size_t length) {
    if (length == 0) return 0;

    // Fast path: if last byte is ASCII, no boundary issues
    if ((unsigned char)buffer[length - 1] < 0x80) {
        return length;
    }

    // Scan backwards (max 4 bytes for UTF-8)
    size_t check_start = length > 4 ? length - 4 : 0;

    for (size_t i = length; i > check_start; ) {
        i--;
        unsigned char b = (unsigned char)buffer[i];

        // Found a lead byte (not a continuation byte 10xxxxxx)
        if ((b & 0xC0) != 0x80) {
            // Determine expected sequence length
            int seq_len;
            if (b < 0x80) {
                seq_len = 1;  // ASCII
            } else if ((b & 0xE0) == 0xC0) {
                seq_len = 2;  // 110xxxxx
            } else if ((b & 0xF0) == 0xE0) {
                seq_len = 3;  // 1110xxxx
            } else if ((b & 0xF8) == 0xF0) {
                seq_len = 4;  // 11110xxx
            } else {
                // Invalid lead byte - treat as boundary here
                return i;
            }

            // Check if we have all bytes for this sequence
            size_t available = length - i;
            if ((size_t)seq_len <= available) {
                // Sequence is complete
                return length;
            } else {
                // Incomplete sequence starts at i
                return i;
            }
        }
    }

    // All bytes in check range are continuation bytes - invalid UTF-8
    return check_start;
}

} // extern "C"
