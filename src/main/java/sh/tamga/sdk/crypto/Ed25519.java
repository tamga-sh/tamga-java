package sh.tamga.sdk.crypto;

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
 */
public final class Ed25519 {

  private static final int SIGNATURE_LENGTH = 64;

  private Ed25519() {
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
