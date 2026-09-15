package com.pharos.compliance.transaction.model;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset-pagination cursor for the transaction evidence endpoints: the {@code (sort_ts,
 * record_key)} of the last row on a page, letting the next request seek directly past it instead
 * of paying an {@code OFFSET} skip-scan cost. Encoded as base64 of {@code
 * "<epoch-millis>|<recordKey>"} — opaque to the client (an implementation detail that may change),
 * but stable enough to round-trip through a URL query parameter.
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
      long epochMillis = Long.parseLong(decoded.substring(0, split));
      String recordKey = decoded.substring(split + 1);
      return new EvidenceCursor(java.time.Instant.ofEpochMilli(epochMillis).atOffset(java.time.ZoneOffset.UTC), recordKey);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Malformed pagination cursor", e);
    }
  }

  public static String encode(OffsetDateTime sortTs, String recordKey) {
    String raw = sortTs.toInstant().toEpochMilli() + DELIMITER + recordKey;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
