package sh.tamga.sdk.crypto;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;

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
 */
public final class Ecdsa {

  private static final String CURVE_NAME = "secp256r1";
  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
  private static final ECParameterSpec P256_PARAMS = loadP256Params();

  private Ecdsa() {
  }

  /**
   * Verifies an ECDSA-P256/SHA-256 signature. Returns {@code false} -- never throws -- for a
   * malformed key/signature, an unparseable SubjectPublicKeyInfo, or a key whose declared curve is
   * not P-256: callers get a uniform, fail-closed boolean result regardless of failure reason.
   *
   * @param publicKeyDer the public key in X.509 SubjectPublicKeyInfo DER encoding.
   * @param message the exact bytes the signature covers.
   * @param signature the signature in ASN.1/DER encoding, as produced by {@code
   *     Signature.getInstance("SHA256withECDSA")}.
   */
  public static boolean verify(byte[] publicKeyDer, byte[] message, byte[] signature) {
    try {
      X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyDer);
      PublicKey key = KeyFactory.getInstance("EC").generatePublic(spec);
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
