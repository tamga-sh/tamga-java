package sh.tamga.sdk.crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * ECDSA-P256/SHA-256 signature verification over the JDK's built-in {@code
 * java.security.Signature} ("SHA256withECDSA").
 *
 * <p><b>SECURITY -- empirically confirmed gap, not assumed:</b> {@code
 * KeyFactory.getInstance("EC").generatePublic(...)} does NOT validate that a parsed X.509
 * SubjectPublicKeyInfo's declared curve OID is actually P-256 -- it happily constructs a key object
 * tagged with whatever curve the SPKI claims. Confirmed directly: a hand-crafted SPKI declaring
 * secp256k1's OID but carrying real P-256 point bytes parses without error, producing a key object
 * whose {@code getParams()} reports secp256k1's parameters (order {@code
 * 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141}, not P-256's). This is the
 * same curve-confusion vulnerability class this SDK family already found and fixed in its
 * python/go/dotnet implementations: a verifier that never pins the expected curve runs ECDSA
 * verification math using whatever curve an attacker-supplied key claims to be, including a
 * deliberately weak one chosen specifically to make forging a signature tractable.
 *
 * <p>Fix: after parsing, explicitly compare the key's {@link ECParameterSpec} against P-256's
 * canonical parameters, field by field. {@code ECParameterSpec} itself does not override {@link
 * Object#equals}, so comparing curve/generator/order/cofactor individually is the portable,
 * provider-independent check -- confirmed empirically that the SunEC provider's own internal {@code
 * NamedCurve} subclass happens to override {@code equals()} usably, but that is an implementation
 * detail of one provider, not a documented contract worth depending on.
 *
 * <p><b>Two key encodings are accepted</b>, because the server distributes ECDSA public keys as a
 * RAW 65-byte SEC1 uncompressed point ({@code 0x04 || X || Y}), not as SPKI: {@code
 * key_material.rs} stores {@code ecdsa_pair.public_key().as_ref()} into {@code
 * accounts.ecdsa_public_key}, and {@code license_signing.rs}'s {@code extract_public_key} returns
 * the same 65 bytes (its own test asserts the length). A verifier that only accepted SPKI could
 * not consume any real account key at all. An SPKI-encoded key is still accepted for callers who
 * converted one themselves. The raw-point path is the safer of the two: the curve is supplied by
 * this class rather than read from an attacker-editable OID in the encoding.
 */
public final class Ecdsa {

  private static final String CURVE_NAME = "secp256r1";
  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
  private static final ECParameterSpec P256_PARAMS = loadP256Params();

  /** {@code 0x04 || X(32) || Y(32)} -- SEC1 section 2.3.3 uncompressed point for P-256. */
  private static final int UNCOMPRESSED_POINT_LENGTH = 65;
  private static final byte UNCOMPRESSED_POINT_TAG = 0x04;
  private static final int COORDINATE_LENGTH = 32;

  private Ecdsa() {
  }

  /**
   * Verifies an ECDSA-P256/SHA-256 signature. Returns {@code false} -- never throws -- for a
   * malformed key/signature, an unparseable key encoding, a point that is not on P-256, or a key
   * whose declared curve is not P-256: callers get a uniform, fail-closed boolean result
   * regardless of failure reason.
   *
   * @param publicKeyDer the public key, either as a raw 65-byte SEC1 uncompressed point (what the
   *     server distributes) or in X.509 SubjectPublicKeyInfo DER encoding.
   * @param message the exact bytes the signature covers.
   * @param signature the signature in ASN.1/DER encoding, as produced by {@code
   *     Signature.getInstance("SHA256withECDSA")}.
   */
  public static boolean verify(byte[] publicKeyDer, byte[] message, byte[] signature) {
    try {
      PublicKey key = parsePublicKey(publicKeyDer);
      if (!(key instanceof ECPublicKey)) {
        return false;
      }
      if (!isP256((ECPublicKey) key)) {
        return false;
      }
      Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static PublicKey parsePublicKey(byte[] encoded) throws GeneralSecurityException {
    KeyFactory factory = KeyFactory.getInstance("EC");
    if (isUncompressedPoint(encoded)) {
      BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 1 + COORDINATE_LENGTH));
      BigInteger y = new BigInteger(1,
          Arrays.copyOfRange(encoded, 1 + COORDINATE_LENGTH, UNCOMPRESSED_POINT_LENGTH));
      // KeyFactory is not contractually required to reject a point that is not on the curve, and
      // an off-curve key is not something the server can ever emit -- reject it here rather than
      // depending on provider behaviour. (For signature VERIFICATION an off-curve key leaks
      // nothing the way an invalid-curve ECDH key would; this is hygiene, not a patched hole.)
      if (!isOnP256(x, y)) {
        throw new InvalidKeySpecException("EC point is not on P-256.");
      }
      return factory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256_PARAMS));
    }
    return factory.generatePublic(new X509EncodedKeySpec(encoded));
  }

  private static boolean isUncompressedPoint(byte[] encoded) {
    return encoded.length == UNCOMPRESSED_POINT_LENGTH && encoded[0] == UNCOMPRESSED_POINT_TAG;
  }

  private static boolean isOnP256(BigInteger x, BigInteger y) {
    EllipticCurve curve = P256_PARAMS.getCurve();
    BigInteger p = ((ECFieldFp) curve.getField()).getP();
    if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
      return false;
    }
    BigInteger left = y.multiply(y).mod(p);
    BigInteger right = x.multiply(x).multiply(x)
        .add(curve.getA().multiply(x))
        .add(curve.getB())
        .mod(p);
    return left.equals(right);
  }

  // NOTE on test coverage (flagged by independent review, addressed by explanation rather than a
  // contrived test): EcdsaTest exercises this method's curve conjunct three ways (a genuinely
  // different curve/P-384; a same-length-coordinates-but-wrong-declared-curve SPKI; a malformed
  // key), which is the conjunct that maps to the actual documented vulnerability class -- an
  // attacker substituting a different curve entirely. Hitting the generator/order/cofactor
  // conjuncts specifically would require a P-256 key whose SPKI carries EXPLICIT (not named-curve)
  // EC domain parameters that reuse P-256's exact field/curve equation but declare a different
  // generator point, order, or cofactor -- encoding valid explicit EC parameters is materially
  // more ASN.1 than the named-curve-OID swap the existing adversarial test uses, is not confirmed
  // to even be accepted by the SunEC provider's SPKI parser (many JCA EC implementations only
  // parse named-curve SPKIs), and has no realistic attacker-reachable path: no real CA or JCA
  // keypair generator ever emits a P-256-field key with a non-standard generator/order/cofactor.
  // Left as a short-circuit-ordered defensive check rather than as a construction worth building a
  // dedicated ASN.1 explicit-parameters encoder to test.
  private static boolean isP256(ECPublicKey key) {
    ECParameterSpec params = key.getParams();
    return params.getCurve().equals(P256_PARAMS.getCurve())
        && params.getGenerator().equals(P256_PARAMS.getGenerator())
        && params.getOrder().equals(P256_PARAMS.getOrder())
        && params.getCofactor() == P256_PARAMS.getCofactor();
  }

  private static ECParameterSpec loadP256Params() {
    try {
      AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance("EC");
      algorithmParameters.init(new ECGenParameterSpec(CURVE_NAME));
      return algorithmParameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      // "EC"/secp256r1 is a mandatory JCA algorithm/curve on every JDK
      // distribution -- not a reachable failure mode, only a broken JVM.
      throw new ExceptionInInitializerError(e);
    }
  }
}
