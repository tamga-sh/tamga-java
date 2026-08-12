package sh.tamga.sdk.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * RSA-2048 PKCS#1 v1.5 and PSS signature verification over the JDK's built-in {@code
 * java.security.Signature}.
 *
 * <p>Explicitly enforces a 2048-bit modulus -- defense-in-depth against the exact bug already
 * found and fixed in this SDK family's Python implementation: an RSA verifier that never checked
 * key size silently accepted a weaker key as if it satisfied the documented {@code RSA_2048_*}
 * scheme family. The scheme names are exact ({@code RSA_2048_PKCS1_SIGN}, {@code
 * RSA_2048_PKCS1_PSS_SIGN}), so the check below is an exact-equality check, not a minimum.
 */
public final class Rsa {

  private static final int REQUIRED_MODULUS_BITS = 2048;
  private static final String PKCS1_ALGORITHM = "SHA256withRSA";
  private static final String PSS_ALGORITHM = "RSASSA-PSS";

  // RSASSA-PSS with SHA-256/MGF1-SHA256/32-byte salt/trailer field 1 (0xBC) --
  // the RFC 8017 recommended default, matching what this SDK family's other
  // implementations (and OpenSSL) use for "RSA-PSS with SHA-256" by default.
  private static final PSSParameterSpec PSS_PARAMETER_SPEC =
      new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);

  private Rsa() {
  }

  /** Verifies an RSA-2048 PKCS#1 v1.5/SHA-256 signature. */
  public static boolean verifyPkcs1(byte[] publicKeyDer, byte[] message, byte[] signature) {
    return verify(publicKeyDer, message, signature, PKCS1_ALGORITHM, null);
  }

  /**
   * Verifies an RSA-2048 PSS/SHA-256 signature. Not interchangeable with {@link #verifyPkcs1} --
   * PKCS#1 v1.5 and PSS signatures over the same message/key never verify against each other.
   */
  public static boolean verifyPss(byte[] publicKeyDer, byte[] message, byte[] signature) {
    return verify(publicKeyDer, message, signature, PSS_ALGORITHM, PSS_PARAMETER_SPEC);
  }

  private static boolean verify(byte[] publicKeyDer, byte[] message, byte[] signature,
      String algorithm, PSSParameterSpec pssParams) {
    try {
      X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyDer);
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
      if (!(key instanceof RSAPublicKey)) {
        return false;
      }
      RSAPublicKey rsaKey = (RSAPublicKey) key;
      if (rsaKey.getModulus().bitLength() != REQUIRED_MODULUS_BITS) {
        return false;
      }
      Signature verifier = Signature.getInstance(algorithm);
      if (pssParams != null) {
        verifier.setParameter(pssParams);
      }
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
