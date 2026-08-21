package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EcdsaTest {

  private static final int UNCOMPRESSED_POINT_LENGTH = 65;

  private static KeyPair generateP256KeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static byte[] signP256(KeyPair keyPair, byte[] message) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(keyPair.getPrivate());
    signer.update(message);
    return signer.sign();
  }

  @Test
  void verifyReturnsTrueForValidSignature() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] message = "tamga machine file payload".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);

    assertThat(Ecdsa.verify(keyPair.getPublic().getEncoded(), message, signature)).isTrue();
  }

  @Test
  void verifyReturnsFalseForTamperedMessage() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] signature = signP256(keyPair, "original".getBytes(StandardCharsets.UTF_8));
    byte[] tamperedMessage = "tampered".getBytes(StandardCharsets.UTF_8);

    boolean result = Ecdsa.verify(keyPair.getPublic().getEncoded(), tamperedMessage, signature);

    assertThat(result).isFalse();
  }

  @Test
  void verifyReturnsFalseForMismatchedKey() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    KeyPair otherKeyPair = generateP256KeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);

    assertThat(Ecdsa.verify(otherKeyPair.getPublic().getEncoded(), message, signature)).isFalse();
  }

  @Test
  void verifyReturnsFalseNotCrashForMalformedPublicKey() {
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);

    assertThat(Ecdsa.verify(new byte[] {1, 2, 3}, message, new byte[] {4, 5, 6})).isFalse();
  }

  /**
   * SECURITY regression (found by independent review, then independently reconfirmed via a
   * standalone probe before trusting the finding): a P-384 key signed AND verified with the
   * literal {@code "SHA384withECDSA"} algorithm is vacuous here -- {@code Signature.verify}
   * itself already rejects the signature on a plain P-256 {@code KeyFactory} parse mismatch
   * before {@link Ecdsa}'s explicit curve-parameter guard ever gets a chance to matter, so the
   * test would still pass even with that guard deleted. Signing AND verifying with {@code
   * "SHA256withECDSA"} specifically -- matching {@link Ecdsa}'s own hardcoded algorithm name --
   * is load-bearing: the JCA's ECDSA verifier runs the digest/verify math generically over
   * whichever curve the key declares, so a P-384 key under {@code "SHA256withECDSA"} verifies
   * {@code true} with the curve guard removed and {@code false} with it present. That is the only
   * construction that actually exercises {@link Ecdsa#verify}'s post-parse curve check rather than
   * a JCA algorithm/key-size mismatch that would fail on its own regardless.
   */
  @Test
  void verifyReturnsFalseForP384KeyEvenWithMatchingSignatureAlgorithm() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp384r1"));
    KeyPair p384KeyPair = generator.generateKeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(p384KeyPair.getPrivate());
    signer.update(message);
    byte[] signature = signer.sign();

    assertThat(Ecdsa.verify(p384KeyPair.getPublic().getEncoded(), message, signature)).isFalse();
  }

  /**
   * SECURITY regression -- empirically confirmed (see Ecdsa's Javadoc): {@code
   * KeyFactory.getInstance("EC").generatePublic(...)} does not validate a parsed SPKI's declared
   * curve OID. A hand-crafted SPKI declaring secp256k1's OID but carrying real P-256 point bytes
   * (same 65-byte coordinate length) must still be rejected by {@link Ecdsa#verify}'s explicit
   * post-parse curve-parameter check.
   */
  @Test
  void verifyReturnsFalseForMismatchedCurveOidEvenWhenCoordinateLengthMatchesP256()
      throws Exception {
    KeyPair p256KeyPair = generateP256KeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(p256KeyPair, message);
    ECPublicKey p256PublicKey = (ECPublicKey) p256KeyPair.getPublic();
    byte[] mismatchedSpki = buildSpkiWithSecp256k1OidButP256Point(p256PublicKey);

    assertThat(Ecdsa.verify(mismatchedSpki, message, signature)).isFalse();
  }

  /**
   * The encoding the server actually distributes: {@code accounts.ecdsa_public_key} stores a raw
   * 65-byte SEC1 uncompressed point, never SPKI. The fixture corpus covers this end to end; this
   * pins the primitive itself so a future "simplify to SPKI only" refactor fails here rather than
   * only in the machine-file suite.
   */
  @Test
  void verifyReturnsTrueForRawSec1UncompressedPoint() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] message = "tamga machine file payload".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);

    assertThat(Ecdsa.verify(rawPoint((ECPublicKey) keyPair.getPublic()), message, signature))
        .isTrue();
  }

  /**
   * A raw point carries no curve OID, so the curve comes from this class -- which means nothing in
   * the encoding constrains the coordinates. A point that does not satisfy P-256's curve equation
   * must be refused rather than handed to a provider that is not contractually required to check.
   */
  @Test
  void verifyReturnsFalseForUncompressedPointThatIsNotOnTheCurve() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);
    byte[] offCurve = rawPoint((ECPublicKey) keyPair.getPublic());
    // Flip a bit in Y: still a well-formed 65-byte uncompressed point, no longer a curve point.
    offCurve[UNCOMPRESSED_POINT_LENGTH - 1] ^= 0x01;

    assertThat(Ecdsa.verify(offCurve, message, signature)).isFalse();
  }

  /**
   * The coordinate range check specifically -- a coordinate at or above the field prime is not a
   * field element at all, and reducing it silently would accept a key the server can never emit.
   */
  @Test
  void verifyReturnsFalseForUncompressedPointWithCoordinateOutsideTheField() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);
    byte[] outOfRangeX = rawPoint((ECPublicKey) keyPair.getPublic());
    byte[] outOfRangeY = rawPoint((ECPublicKey) keyPair.getPublic());
    for (int i = 1; i <= 32; i++) {
      outOfRangeX[i] = (byte) 0xFF;
      outOfRangeY[i + 32] = (byte) 0xFF;
    }

    assertThat(Ecdsa.verify(outOfRangeX, message, signature)).isFalse();
    assertThat(Ecdsa.verify(outOfRangeY, message, signature)).isFalse();
  }

  /**
   * 65 bytes alone does not make a raw point: without the {@code 0x04} uncompressed-point tag the
   * bytes fall through to the SPKI parser, which rejects them. Nothing may guess.
   */
  @Test
  void verifyReturnsFalseFor65ByteKeyWithoutTheUncompressedPointTag() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signP256(keyPair, message);
    byte[] untagged = rawPoint((ECPublicKey) keyPair.getPublic());
    untagged[0] = 0x02;

    assertThat(Ecdsa.verify(untagged, message, signature)).isFalse();
  }

  // --- Hand-rolled DER/ASN.1 construction for the adversarial mismatched-OID SPKI. Not a
  // general-purpose ASN.1 encoder -- scoped to exactly this one test's needs. ---

  /** {@code 0x04 || X(32) || Y(32)} -- SEC1 section 2.3.3 uncompressed point for P-256. */
  private static byte[] rawPoint(ECPublicKey key) {
    int fieldSizeBytes = (key.getParams().getCurve().getField().getFieldSize() + 7) / 8;
    return concat(new byte[] {0x04}, toFixedLength(key.getW().getAffineX(), fieldSizeBytes),
        toFixedLength(key.getW().getAffineY(), fieldSizeBytes));
  }

  private static byte[] buildSpkiWithSecp256k1OidButP256Point(ECPublicKey p256Key) {
    int fieldSizeBytes = (p256Key.getParams().getCurve().getField().getFieldSize() + 7) / 8;
    byte[] pointBytesX = toFixedLength(p256Key.getW().getAffineX(), fieldSizeBytes);
    byte[] pointBytesY = toFixedLength(p256Key.getW().getAffineY(), fieldSizeBytes);
    byte[] rawPoint = concat(new byte[] {0x04}, pointBytesX, pointBytesY);

    byte[] idEcPublicKeyOid = oidBytes("1.2.840.10045.2.1");
    byte[] secp256k1Oid = oidBytes("1.3.132.0.10");
    byte[] algOids = concat(tlv(0x06, idEcPublicKeyOid), tlv(0x06, secp256k1Oid));
    byte[] algorithmIdentifier = tlv(0x30, algOids);
    byte[] bitString = tlv(0x03, concat(new byte[] {0x00}, rawPoint));
    return tlv(0x30, concat(algorithmIdentifier, bitString));
  }

  private static byte[] toFixedLength(BigInteger value, int length) {
    byte[] raw = value.toByteArray();
    if (raw.length == length) {
      return raw;
    }
    byte[] out = new byte[length];
    if (raw.length > length) {
      System.arraycopy(raw, raw.length - length, out, 0, length);
    } else {
      System.arraycopy(raw, 0, out, length - raw.length, raw.length);
    }
    return out;
  }

  private static byte[] oidBytes(String dotted) {
    String[] parts = dotted.split("\\.");
    int first = Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    for (int i = 2; i < parts.length; i++) {
      out.writeBytes(encodeBase128(Long.parseLong(parts[i])));
    }
    return out.toByteArray();
  }

  private static byte[] encodeBase128(long value) {
    if (value == 0) {
      return new byte[] {0};
    }
    List<Byte> bytes = new ArrayList<>();
    long remaining = value;
    while (remaining > 0) {
      bytes.add(0, (byte) (remaining & 0x7F));
      remaining >>= 7;
    }
    for (int i = 0; i < bytes.size() - 1; i++) {
      bytes.set(i, (byte) (bytes.get(i) | 0x80));
    }
    byte[] out = new byte[bytes.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = bytes.get(i);
    }
    return out;
  }

  private static byte[] derLength(int length) {
    if (length < 128) {
      return new byte[] {(byte) length};
    }
    byte[] valueBytes = BigInteger.valueOf(length).toByteArray();
    int offset = valueBytes[0] == 0 ? 1 : 0;
    byte[] out = new byte[1 + (valueBytes.length - offset)];
    out[0] = (byte) (0x80 | (valueBytes.length - offset));
    System.arraycopy(valueBytes, offset, out, 1, valueBytes.length - offset);
    return out;
  }

  private static byte[] tlv(int tag, byte[] content) {
    return concat(new byte[] {(byte) tag}, derLength(content.length), content);
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (byte[] part : parts) {
      total += part.length;
    }
    byte[] out = new byte[total];
    int position = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, position, part.length);
      position += part.length;
    }
    return out;
  }
}
