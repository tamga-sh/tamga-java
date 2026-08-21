package sh.tamga.sdk.crypto;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.bouncycastle.asn1.ASN1Primitive;

/**
 * RSA-2048 PKCS#1 v1.5 and PSS signature verification over the JDK's built-in {@code
 * java.security.Signature}.
 *
 * <p>Explicitly enforces a 2048-bit modulus -- defense-in-depth against the exact bug already
 * found and fixed in this SDK family's Python implementation: an RSA verifier that never checked
 * key size silently accepted a weaker key as if it satisfied the documented {@code RSA_2048_*}
 * scheme family. The scheme names are exact ({@code RSA_2048_PKCS1_SIGN}, {@code
 * RSA_2048_PKCS1_PSS_SIGN}), so the check below is an exact-equality check, not a minimum.
 *
 * <p><b>Two key encodings are accepted</b>, because the server emits both for the same key and
 * does not say which a given caller was handed: {@code key_material.rs} stores {@code
 * rsa_pair.public_key().as_der()} -- X.509 {@code SubjectPublicKeyInfo} -- into {@code
 * accounts.public_key}, while {@code license_signing.rs}'s {@code extract_public_key} returns
 * {@code kp.public_key().as_ref()}, which is a PKCS#1 {@code RSAPublicKey} ({@code SEQUENCE
 * {modulus, publicExponent}}, 270 bytes for RSA-2048) and is also the encoding the server's own
 * {@code verify} helper expects. SPKI is tried first; a PKCS#1 body is decoded and rebuilt into a
 * key spec. Accepting both is not a weakening -- they encode identical key material, and the
 * 2048-bit check applies to whichever path produced the key.
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

  /**
   * Verifies an RSA-2048 PKCS#1 v1.5/SHA-256 signature.
   *
   * @param publicKeyDer the public key, either as X.509 SubjectPublicKeyInfo DER or as a PKCS#1
   *     {@code RSAPublicKey} DER body -- the server emits both, see the type-level remarks.
   */
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
      PublicKey key = parsePublicKey(publicKeyDer);
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

  private static PublicKey parsePublicKey(byte[] encoded) throws GeneralSecurityException {
    KeyFactory factory = KeyFactory.getInstance("RSA");
    try {
      return factory.generatePublic(new X509EncodedKeySpec(encoded));
    } catch (InvalidKeySpecException e) {
      return factory.generatePublic(pkcs1KeySpec(encoded));
    }
  }

  /**
   * Decodes a PKCS#1 {@code RSAPublicKey} ({@code SEQUENCE {INTEGER modulus, INTEGER
   * publicExponent}}) into a key spec.
   *
   * <p>Uses BouncyCastle's ASN.1 reader rather than a hand-rolled DER parser. BouncyCastle is
   * already a first-class dependency of this module (for Ed25519) and its structural decoder is
   * far better tested than anything worth writing here; the crypto itself still runs on the JDK's
   * own provider, and BouncyCastle is still never registered as a JCA {@code Provider}.
   */
  private static KeySpec pkcs1KeySpec(byte[] encoded) throws InvalidKeySpecException {
    try {
      org.bouncycastle.asn1.pkcs.RSAPublicKey parsed =
          org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(ASN1Primitive.fromByteArray(encoded));
      return new RSAPublicKeySpec(parsed.getModulus(), parsed.getPublicExponent());
    } catch (IOException | IllegalArgumentException | IllegalStateException e) {
      throw new InvalidKeySpecException(
          "Public key is neither X.509 SubjectPublicKeyInfo nor PKCS#1 RSAPublicKey DER.", e);
    }
  }
}
