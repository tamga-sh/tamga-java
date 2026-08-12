package sh.tamga.sdk.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * License-file AES-256-GCM key derivation: the license key's raw UTF-8 bytes, zero-padded or
 * truncated to exactly 32 bytes.
 *
 * <p><b>CRITICAL:</b> this is explicitly NOT a KDF -- matches the server's own derivation for this
 * wire format exactly. Running the license key through SHA-256 or any real KDF produces a key
 * that silently fails to decrypt every plain license file. Contrast with {@link Hkdf}, which
 * machine-file checkout uses instead -- the two derivations are never interchangeable.
 */
public final class NaiveKey {

  private static final int KEY_LENGTH = 32;

  private NaiveKey() {
  }

  /**
   * Zero-pads (if shorter than 32 bytes) or truncates (if longer) the license key's raw UTF-8
   * bytes to exactly 32 bytes.
   */
  public static byte[] derive(String licenseKey) {
    byte[] raw = licenseKey.getBytes(StandardCharsets.UTF_8);
    // Arrays.copyOf zero-pads on growth and truncates on shrink -- exactly
    // the "zero-pad or truncate" semantics this derivation needs, in one call.
    return Arrays.copyOf(raw, KEY_LENGTH);
  }
}
