package com.pharos.compliance.transaction.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EvidenceCursorTest {
  @Test
  void timestampCursorPreservesPostgresMicrosecondPrecision() {
    OffsetDateTime timestamp = OffsetDateTime.of(2026, 8, 24, 12, 30, 15, 123_456_000, ZoneOffset.UTC);

    EvidenceCursor decoded = EvidenceCursor.decode(EvidenceCursor.encode(timestamp, "record-1"));

    assertEquals(timestamp, decoded.sortTs());
    assertEquals("record-1", decoded.recordKey());
  }

  @Test
  void existingEpochMillisecondCursorStillDecodes() {
    OffsetDateTime timestamp = OffsetDateTime.of(2026, 8, 24, 12, 30, 15, 123_000_000, ZoneOffset.UTC);
    String raw = timestamp.toInstant().toEpochMilli() + "|record-legacy";
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

    EvidenceCursor decoded = EvidenceCursor.decode(token);

    assertEquals(timestamp, decoded.sortTs());
    assertEquals("record-legacy", decoded.recordKey());
  }

  @Test
  void nullTimestampCursorRoundTripsForTheNullsLastTail() {
    EvidenceCursor decoded = EvidenceCursor.decode(EvidenceCursor.encode(null, "record-2"));

    assertNull(decoded.sortTs());
    assertEquals("record-2", decoded.recordKey());
  }
}
