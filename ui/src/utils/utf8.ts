// SPDX-License-Identifier: Apache-2.0
//
// UTF-8 byte lengths. The server counts its text limits in UTF-8 bytes (docs/14 §7.2, SEC-64:
// free-text filters 1 KB, QueryLimits.MAX_TEXT_FILTER_BYTES), so the UI measures the same way
// before it writes a value into the URL or a request: one Hangul syllable is 3 bytes, an emoji 4.

const ENCODER = new TextEncoder();

/** Length of a string in UTF-8 bytes. */
export function utf8ByteLength(text: string): number {
  return ENCODER.encode(text).length;
}

/**
 * The longest prefix of `text` of at most `maxBytes` UTF-8 bytes. A surrogate pair (emoji) is never
 * split: it is kept whole or dropped whole.
 */
export function truncateUtf8(text: string, maxBytes: number): string {
  if (utf8ByteLength(text) <= maxBytes) {
    return text;
  }
  let bytes = 0;
  let end = 0;
  for (const char of text) {
    const size = utf8ByteLength(char);
    if (bytes + size > maxBytes) {
      break;
    }
    bytes += size;
    end += char.length;
  }
  return text.slice(0, end);
}
