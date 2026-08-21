package sh.tamga.sdk.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Ed25519 signature verification via BouncyCastle's lightweight API ({@code Ed25519Signer} +
 * {@code Ed25519PublicKeyParameters}) -- not registered as a JCA {@code Provider}, so this is the
 * only class in this SDK that imports BouncyCastle directly.
 *
 * <p>Used because the JDK's own built-in EdDSA support ({@code java.security.Signature}/{@code
 * KeyFactory} with algorithm name {@code "Ed25519"}) only landed in JDK 15 (JEP 339), but this
 * module's bytecode target stays at Java 11 so consuming applications aren't forced onto a newer
 * JVM -- see {@code build.gradle.kts}. Every other primitive in this package uses a JDK built-in;
 * this is the one genuine gap, and the dependency is scoped to just this primitive rather than
 * registered platform-wide.
 *
 * <p>{@link #keyId(String)} sits here too and uses no BouncyCastle at all -- it is a JDK {@code
 * SHA-256} digest over an Ed25519 public key, and it lives with the primitive whose keys it names
 * rather than in a class of its own.
 */
public final class Ed25519 {

  /**
   * The {@code kid} every file signed by an account whose {@code ed25519_public_key} column was
   * never populated carries: {@link #keyId(String)} of the empty string.
   *
   * <p>Not a hypothetical. Both checkout handlers derive the claim as {@code
   * key_id(account.ed25519_public_key.as_deref().unwrap_or_default())} ({@code
   * check_out_license.rs:95-97}, {@code check_out_machine.rs:127-129}) while signing with the
   * PRIVATE half, which is required and non-null -- so such a file is genuinely signed by a real
   * key and its {@code kid} names nothing at all. Seeing this value is the difference between
   * "my key set is stale" and "this server has published no key to match against"; the first is
   * fixed by fetching the key set again, the second only by an operator populating the column.
   */
  public static final String UNPUBLISHED_ACCOUNT_KEY_ID = "e3b0c44298fc1c14";

  private static final int SIGNATURE_LENGTH = 64;

  /** Bytes of the SHA-256 digest that make up a key id -- 8 bytes, so 16 hex characters. */
  private static final int KEY_ID_BYTES = 8;

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private Ed25519() {
  }

  /**
   * The {@code kid} an offline file names for a given Ed25519 public key: the first eight bytes of
   * {@code SHA-256} over the key, lowercase hex, so exactly sixteen characters.
   *
   * <p>Mirrors the server's own {@code key_id} ({@code
   * tamga-api/src/shared/crypto/license_file.rs:70-77}) exactly. Two details are easy to get
   * wrong and both are pinned by {@code Ed25519KeyIdTest}:
   *
   * <ul>
   *   <li><b>The digest covers the base64 STRING's UTF-8 bytes, never the 32 decoded key
   *       bytes.</b> The server stores the public half as base64 and hands that same {@code &str}
   *       to {@code key_id}. Decoding first produces a different, wrong id -- the same class of
   *       trap as the signature covering {@code enc}'s base64 string rather than its decoding.
   *   <li>Eight <em>bytes</em>, sixteen hex characters -- not eight characters. Each byte is
   *       zero-padded and masked to eight bits, so a byte {@code >= 0x80} does not sign-extend.
   * </ul>
   *
   * <p>Because the id is a pure function of the key, an application that pins a public key in its
   * binary can compute the id its files will name without any network access -- which is what
   * {@link sh.tamga.sdk.checkout.SigningKeySet} is built on. Against the account's published key
   * set the id arrives as the resource {@code id} already, and this is a cross-check rather than a
   * requirement.
   *
   * @param publicKeyBase64 the public key exactly as the server publishes and stores it: standard
   *     base64 of the raw 32 bytes. Must not be null.
   */
  public static String keyId(String publicKeyBase64) {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // Every Java SE implementation is required to provide SHA-256, so this cannot happen on a
      // conforming JVM -- but it is a checked exception and swallowing it would be worse.
      throw new IllegalStateException("SHA-256 is unavailable in this JVM.", e);
    }
    byte[] digest = sha256.digest(publicKeyBase64.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(KEY_ID_BYTES * 2);
    for (int i = 0; i < KEY_ID_BYTES; i++) {
      hex.append(HEX_DIGITS[(digest[i] >> 4) & 0x0f]).append(HEX_DIGITS[digest[i] & 0x0f]);
    }
    return hex.toString();
  }

  /**
   * Verifies an Ed25519 signature. Returns {@code false} -- never throws -- for a malformed public
   * key or signature: callers get a uniform, fail-closed boolean result regardless of failure
   * reason.
   *
   * @param publicKey raw 32-byte Ed25519 public key.
   * @param message the exact bytes the signature covers.
   * @param signature raw 64-byte Ed25519 signature.
   */
  public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
    boolean lengthsValid = publicKey.length == Ed25519PublicKeyParameters.KEY_SIZE
        && signature.length == SIGNATURE_LENGTH;
    if (!lengthsValid) {
      return false;
    }
    try {
      Ed25519PublicKeyParameters keyParameters = new Ed25519PublicKeyParameters(publicKey, 0);
      Ed25519Signer verifier = new Ed25519Signer();
      verifier.init(false, keyParameters);
      verifier.update(message, 0, message.length);
      return verifier.verifySignature(signature);
    } catch (RuntimeException e) {
      // BouncyCastle's lightweight API throws unchecked exceptions (e.g.
      // IllegalArgumentException) for malformed input rather than exposing a
      // stable checked-exception contract -- fail closed instead of
      // propagating an implementation-detail exception type to callers.
      return false;
    }
  }
}
