package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Placeholder smoke test so {@code ./gradlew test} has something to run on day one.
 * Replace/delete once real tests land for the HTTP-facing surface -- this test asserts nothing
 * about SDK behavior, only that the JUnit 5 + AssertJ + Mockito toolchain and the coverage/report
 * wiring in {@code build.gradle.kts} actually work.
 */
class SmokeTest {

  @Test
  void toolchainIsWired() {
    assertThat(1 + 1).isEqualTo(2);
  }
}
