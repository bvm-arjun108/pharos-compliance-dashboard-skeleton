package com.pharos.compliance.transaction.model;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Opaque keyset-pagination cursor for the transaction evidence endpoints: the {@code (sort_ts,
 * record_key)} of the last row on a page, letting the next request seek directly past it instead
 * of paying an {@code OFFSET} skip-scan cost. Encoded as base64 of {@code
 * "<ISO-8601 timestamp>|<recordKey>"} — opaque to the client (an implementation detail that may
 * change), but stable enough to round-trip through a URL query parameter. New cursors retain the
 * complete timestamp precision returned by PostgreSQL; decoding remains backward-compatible with
 * the original epoch-millisecond representation.
 *
 * <p>This is the cursor half of the hybrid offset/cursor pagination contract: the page-number
 * paginator UI keeps using {@code page}/{@code size} (offset) since jumping to an arbitrary page
 * number has no cursor equivalent (you'd need to already know how many rows precede it — the very
 * cost cursor pagination avoids). Cursor mode is for genuinely sequential access — walking forward
 * through a very large batch or period without ever paying for the rows already seen.
 */
public record EvidenceCursor(OffsetDateTime sortTs, String recordKey) {
  private static final String DELIMITER = "|";

  /**
   * Returns null for a blank/absent cursor — the normal "first page" case.
   */
  public static EvidenceCursor decode(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String decoded;
    try {
      decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed pagination cursor", e);
    }
    int split = decoded.indexOf(DELIMITER);
    if (split < 0) {
      throw new IllegalArgumentException("Malformed pagination cursor");
    }
    try {
      String encodedTimestamp = decoded.substring(0, split);
      String recordKey = decoded.substring(split + 1);
      OffsetDateTime sortTs = decodeTimestamp(encodedTimestamp);
      return new EvidenceCursor(sortTs, recordKey);
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new IllegalArgumentException("Malformed pagination cursor", e);
    }
  }

  private static OffsetDateTime decodeTimestamp(String encodedTimestamp) {
    if (encodedTimestamp.isEmpty()) {
      return null;
    }
    if (encodedTimestamp.indexOf('T') >= 0) {
      return OffsetDateTime.parse(encodedTimestamp);
    }
    return java.time.Instant.ofEpochMilli(Long.parseLong(encodedTimestamp)).atOffset(java.time.ZoneOffset.UTC);
  }

  public static String encode(OffsetDateTime sortTs, String recordKey) {
    String encodedTimestamp = sortTs == null ? "" : sortTs.toString();
    String raw = encodedTimestamp + DELIMITER + recordKey;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
