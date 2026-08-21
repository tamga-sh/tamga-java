package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code x-ratelimit-*} view and its interaction with {@link ResponseMetadata}.
 *
 * <p>The point of the type is that "unknown" and "zero" are different answers: a bucket with no
 * requests left reports {@code 0}, and a server with rate limiting switched off reports nothing at
 * all. Both are pinned here, as is the absolute-timestamp reading of {@code reset}.
 */
class RateLimitInfoTest {

  @Test
  void readsTheFourHeadersInOrder() {
    RateLimitInfo info = RateLimitInfo.fromHeaders("100", "97", "1755763200", "1");

    assertThat(info.isPresent()).isTrue();
    assertThat(info.limit()).isEqualTo(100L);
    assertThat(info.remaining()).isEqualTo(97L);
    assertThat(info.resetAt()).isEqualTo(1_755_763_200L);
    assertThat(info.window()).isEqualTo(1L);
  }

  @Test
  void anEmptyBucketIsZeroRemainingNotAbsent() {
    RateLimitInfo info = RateLimitInfo.fromHeaders("100", "0", "1755763200", "1");

    // Confusing these two is the whole reason ABSENT is -1: a caller reading 0 must slow down,
    // a caller reading ABSENT knows nothing and must not infer exhaustion.
    assertThat(info.remaining()).isZero();
    assertThat(info.remaining()).isNotEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.isPresent()).isTrue();
  }

  @Test
  void everyFieldIsAbsentWhenTheServerSendsNothing() {
    RateLimitInfo info = RateLimitInfo.fromHeaders(null, null, null, null);

    // Rate limiting is disabled outright when the limiter could not be built, and the middleware
    // then returns before writing a single one of the four.
    assertThat(info.isPresent()).isFalse();
    assertThat(info.limit()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.remaining()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.resetAt()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.window()).isEqualTo(RateLimitInfo.ABSENT);
  }

  @Test
  void absentIsTheSameValueTheNoHeaderCaseProduces() {
    RateLimitInfo absent = RateLimitInfo.absent();

    assertThat(absent.isPresent()).isFalse();
    assertThat(absent.limit()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(absent.window()).isEqualTo(RateLimitInfo.ABSENT);
  }

  @Test
  void oneUsableHeaderIsEnoughToCountAsPresent() {
    assertThat(RateLimitInfo.fromHeaders("60", null, null, null).isPresent()).isTrue();
    assertThat(RateLimitInfo.fromHeaders(null, "7", null, null).isPresent()).isTrue();
    assertThat(RateLimitInfo.fromHeaders(null, null, "1755763200", null).isPresent()).isTrue();
    assertThat(RateLimitInfo.fromHeaders(null, null, null, "1").isPresent()).isTrue();
  }

  @Test
  void unusableValuesDegradeToAbsentRatherThanThrowing() {
    // A broken or hostile proxy rewriting a diagnostic header must not turn a usable response
    // into a client-side failure.
    RateLimitInfo info = RateLimitInfo.fromHeaders("", "  ", "not-a-number", "-1");

    assertThat(info.isPresent()).isFalse();
    assertThat(info.remaining()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.resetAt()).isEqualTo(RateLimitInfo.ABSENT);
    assertThat(info.window()).isEqualTo(RateLimitInfo.ABSENT);
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertThat(RateLimitInfo.fromHeaders(" 60 ", null, null, null).limit()).isEqualTo(60L);
  }

  @Test
  void hugeValuesAreAbsentRatherThanTruncated() {
    assertThat(RateLimitInfo.fromHeaders("99999999999999999999", null, null, null).limit())
        .isEqualTo(RateLimitInfo.ABSENT);
  }

  @Test
  void resetIsAnAbsoluteTimestampRatherThanDelay() {
    // The server computes it as now + the bucket's remaining TTL, so it is far in the future in
    // epoch seconds. Sleeping for it directly would park the caller for decades.
    long resetAt = RateLimitInfo.fromHeaders("100", "0", "1755763200", "1").resetAt();

    assertThat(resetAt).isGreaterThan(1_700_000_000L);
  }

  @Test
  void theFourArgumentMetadataConstructorStillCompilesAndReportsAbsent() {
    // Source and binary compatibility for anything written against 1.3.x.
    ResponseMetadata metadata = new ResponseMetadata("1.8", "EE", "multiplayer", "req-1");

    assertThat(metadata.requestId()).isEqualTo("req-1");
    assertThat(metadata.rateLimit()).isNotNull();
    assertThat(metadata.rateLimit().isPresent()).isFalse();
  }

  @Test
  void theFiveArgumentMetadataConstructorCarriesTheRateLimitView() {
    ResponseMetadata metadata = new ResponseMetadata("1.8", "CE", "singleplayer", "req-2",
        RateLimitInfo.fromHeaders("10", "3", "1755763200", "1"));

    assertThat(metadata.tamgaEdition()).isEqualTo("CE");
    assertThat(metadata.rateLimit().remaining()).isEqualTo(3L);
  }

  @Test
  void nullRateLimitViewIsNormalizedRatherThanStored() {
    ResponseMetadata metadata = new ResponseMetadata(null, null, null, null, null);

    assertThat(metadata.tamgaVersion()).isEmpty();
    assertThat(metadata.rateLimit().isPresent()).isFalse();
  }
}
