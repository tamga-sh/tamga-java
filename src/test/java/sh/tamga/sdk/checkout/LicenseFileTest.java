package sh.tamga.sdk.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.crypto.NaiveKey;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.support.CheckoutFixture;

class LicenseFileTest {

  private static Ed25519PrivateKeyParameters generateKey() {
    return new Ed25519PrivateKeyParameters(new SecureRandom());
  }

  @Test
  void parseAndVerifySucceedsForValidPlainFile() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TEST-LICENSE-KEY");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(key.generatePublicKey().getEncoded())).isTrue();
  }

  @Test
  void verifyAndDecryptDecodesEveryLicenseField() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.fullLicensePayloadJson("TAMGA-FULL-FIELDS");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();
    License license = file.verifyAndDecrypt(publicKey, "unused-for-plain");

    assertThat(license.uses()).isEqualTo(3);
    assertThat(license.expiry()).isNotNull();
    assertThat(license.lastValidatedAt()).isNotNull();
    assertThat(license.lastCheckInAt()).isNotNull();
    assertThat(license.metadata()).containsEntry("seats", 5);
    assertThat(license.metadata()).containsEntry("tier", "pro");
    assertThat(license.metadata()).containsEntry("trial", false);
    assertThat(license.metadata()).containsKey("note");
  }

  @Test
  void verifyAndDecryptReturnsLicenseForValidPlainFile() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TAMGA-ABC-123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();
    License license = file.verifyAndDecrypt(publicKey, "unused-for-plain");

    assertThat(license.id()).isEqualTo("lic_123");
    assertThat(license.key()).isEqualTo("TAMGA-ABC-123");
    assertThat(license.suspended()).isFalse();
  }

  @Test
  void verifyAndDecryptReturnsLicenseForValidEncryptedFile() {
    Ed25519PrivateKeyParameters signingKey = generateKey();
    String licenseKey = "TAMGA-ENCRYPTED-KEY";
    byte[] aesKey = NaiveKey.derive(licenseKey);
    byte[] json = CheckoutFixture.licensePayloadJson(licenseKey);
    String enc = CheckoutFixture.encryptedEnc(json, aesKey);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "aes-256-gcm+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = signingKey.generatePublicKey().getEncoded();
    License license = file.verifyAndDecrypt(publicKey, licenseKey);

    assertThat(license.key()).isEqualTo(licenseKey);
  }

  @Test
  void verifyAndDecryptThrowsSignatureVerificationExceptionForWrongPublicKey() {
    Ed25519PrivateKeyParameters signingKey = generateKey();
    Ed25519PrivateKeyParameters otherKey = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TEST-LICENSE-KEY");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] wrongPublicKey = otherKey.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(wrongPublicKey, "unused-for-plain"))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void verifyAndDecryptThrowsDecryptionExceptionForWrongLicenseKeyOnEncryptedFile() {
    Ed25519PrivateKeyParameters signingKey = generateKey();
    byte[] aesKey = NaiveKey.derive("REAL-KEY");
    byte[] json = CheckoutFixture.licensePayloadJson("REAL-KEY");
    String enc = CheckoutFixture.encryptedEnc(json, aesKey);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "aes-256-gcm+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = signingKey.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(publicKey, "WRONG-KEY"))
        .isInstanceOf(TamgaCheckoutException.DecryptionException.class);
  }

  /**
   * CRITICAL regression: the Ed25519 signature must cover {@code enc}'s base64 STRING bytes, not
   * the decoded payload bytes. Confirm a signature computed over the DECODED bytes (the common
   * implementation mistake) does NOT verify against this SDK's {@link LicenseFile#verify}, which
   * signs/verifies over the string form.
   */
  @Test
  void verifyFailsWhenSignatureWasComputedOverDecodedBytesNotEncsBase64StringBytes() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TEST-LICENSE-KEY");
    String enc = CheckoutFixture.plainEnc(json);

    // Deliberately sign the DECODED bytes (json) instead of enc's string bytes.
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, key);
    signer.update(json, 0, json.length);
    String sig = Base64.getEncoder().encodeToString(signer.generateSignature());
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(key.generatePublicKey().getEncoded())).isFalse();
  }

  @Test
  void verifyReturnsFalseForTamperedEncPayload() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TEST-LICENSE-KEY");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc + "tampered", sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(key.generatePublicKey().getEncoded())).isFalse();
  }

  @Test
  void verifyReturnsFalseForWrongPublicKey() {
    Ed25519PrivateKeyParameters signingKey = generateKey();
    Ed25519PrivateKeyParameters otherKey = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson("TEST-LICENSE-KEY");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(otherKey.generatePublicKey().getEncoded())).isFalse();
  }

  @Test
  void verifyReturnsFalseForMalformedBase64Signature() {
    Ed25519PrivateKeyParameters key = generateKey();
    String pem = CheckoutFixture.wrapLicensePem("AA==", "not valid base64!!!", "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(key.generatePublicKey().getEncoded())).isFalse();
  }

  @Test
  void verifyThrowsForNonEd25519Alg() {
    Ed25519PrivateKeyParameters key = generateKey();
    String pem = CheckoutFixture.wrapLicensePem("AA==", "AA==", "base64+rsa");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verify(publicKey))
        .isInstanceOf(TamgaCheckoutException.UnsupportedAlgorithmException.class);
  }

  @Test
  void verifyAndDecryptThrowsForMalformedEncBase64() {
    Ed25519PrivateKeyParameters key = generateKey();
    String enc = "not valid base64!!!";
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(publicKey, "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void verifyAndDecryptThrowsForMalformedPayloadJson() {
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] notResourceJson = "not resource json".getBytes(StandardCharsets.UTF_8);
    String enc = Base64.getEncoder().encodeToString(notResourceJson);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "base64+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(publicKey, "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void verifyAndDecryptThrowsForShortEncryptedPayload() {
    Ed25519PrivateKeyParameters key = generateKey();
    String enc = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapLicensePem(enc, sig, "aes-256-gcm+ed25519");

    LicenseFile file = LicenseFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(publicKey, "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMissingBeginMarker() {
    assertThatThrownBy(() -> LicenseFile.parse("not a pem file\n-----END LICENSE FILE-----"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMissingEndMarker() {
    assertThatThrownBy(() -> LicenseFile.parse("-----BEGIN LICENSE FILE-----\nnot a pem file"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  /**
   * SECURITY regression: a crafted string shorter than {@code beginMarker.length() +
   * endMarker.length()} that still independently satisfies startsWith/endsWith must not crash the
   * length computation -- see {@link PemEnvelope}'s guard.
   */
  @Test
  void parseThrowsForShortOverlappingMarkers() {
    String begin = "-----BEGIN LICENSE FILE-----";
    String end = "-----END LICENSE FILE-----";
    String overlapping = begin + end.substring(5);

    assertThatThrownBy(() -> LicenseFile.parse(overlapping))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMalformedBase64Body() {
    String pem = "-----BEGIN LICENSE FILE-----\nnot valid base64!!!\n-----END LICENSE FILE-----";

    assertThatThrownBy(() -> LicenseFile.parse(pem))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMalformedCertificateJson() {
    byte[] notCertificate = "not a certificate".getBytes(StandardCharsets.UTF_8);
    String body = Base64.getEncoder().encodeToString(notCertificate);
    String pem = "-----BEGIN LICENSE FILE-----\n" + body + "\n-----END LICENSE FILE-----";

    assertThatThrownBy(() -> LicenseFile.parse(pem))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }
}
