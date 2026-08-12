package sh.tamga.sdk.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.crypto.Hkdf;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.LicenseScheme;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.support.CheckoutFixture;

class MachineFileTest {

  private static Ed25519PrivateKeyParameters generateEd25519Key() {
    return new Ed25519PrivateKeyParameters(new SecureRandom());
  }

  private static KeyPair generateP256KeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  @Test
  void verifySucceedsForValidEd25519SignedFile() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThat(file.verify(LicenseScheme.ED25519_SIGN, publicKey)).isTrue();
  }

  @Test
  void verifySucceedsForValidEcdsaP256SignedFile() throws Exception {
    KeyPair keyPair = generateP256KeyPair();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ecdsaSign(enc, keyPair.getPrivate());
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "ecdsa-sha256");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    assertThat(file.verify(LicenseScheme.ECDSA_P256_SIGN, publicKey)).isTrue();
  }

  @Test
  void verifySucceedsForValidRsaPkcs1SignedFile() throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.rsaPkcs1Sign(enc, keyPair.getPrivate());
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "rsa-sha256");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    assertThat(file.verify(LicenseScheme.RSA_2048_PKCS1_SIGN, publicKey)).isTrue();
  }

  @Test
  void verifySucceedsForValidRsaPssSignedFile() throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.rsaPssSign(enc, keyPair.getPrivate());
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "rsa-pss-sha256");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    assertThat(file.verify(LicenseScheme.RSA_2048_PKCS1_PSS_SIGN, publicKey)).isTrue();
  }

  @Test
  void verifyTreatsNoneSchemeSameAsEd25519() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThat(file.verify(LicenseScheme.NONE, publicKey)).isTrue();
  }

  /**
   * GOTCHA regression: RSA_2048_JWT_RS256 must be explicitly rejected, never silently routed to
   * another RSA verifier -- the algorithm-confusion risk this SDK family's own docs call out
   * repeatedly.
   */
  @Test
  void verifyThrowsForJwtRs256SchemeNeverAttemptingVerification() throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    byte[] json = CheckoutFixture.machinePayloadJson("fp-abc123");
    String enc = CheckoutFixture.plainEnc(json);
    // Even a validly-signed PKCS1 signature must not slip through under the JWT scheme.
    String sig = CheckoutFixture.rsaPkcs1Sign(enc, keyPair.getPrivate());
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "rsa-sha256");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    assertThatThrownBy(() -> file.verify(LicenseScheme.RSA_2048_JWT_RS256, publicKey))
        .isInstanceOf(TamgaCheckoutException.SchemeNotSupportedException.class);
  }

  @Test
  void verifyAndDecryptReturnsMachineForValidPlainFile() {
    Ed25519PrivateKeyParameters signingKey = generateEd25519Key();
    String fingerprint = "plain-fingerprint-xyz";
    byte[] json = CheckoutFixture.machinePayloadJson(fingerprint);
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = signingKey.generatePublicKey().getEncoded();
    Machine machine = file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey,
        "unused-for-plain", fingerprint);

    assertThat(machine.id()).isEqualTo("mach_123");
    assertThat(machine.fingerprint()).isEqualTo(fingerprint);
  }

  @Test
  void verifyAndDecryptThrowsSignatureVerificationExceptionForWrongPublicKey() {
    Ed25519PrivateKeyParameters signingKey = generateEd25519Key();
    Ed25519PrivateKeyParameters otherKey = generateEd25519Key();
    String fingerprint = "fp-abc123";
    byte[] json = CheckoutFixture.machinePayloadJson(fingerprint);
    String enc = CheckoutFixture.plainEnc(json);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] wrongPublicKey = otherKey.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, wrongPublicKey,
        "unused-for-plain", fingerprint))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void verifyAndDecryptReturnsMachineForValidEncryptedFile() {
    Ed25519PrivateKeyParameters signingKey = generateEd25519Key();
    String licenseKey = "TAMGA-LICENSE-KEY";
    String fingerprint = "machine-fingerprint-xyz";
    byte[] aesKey = Hkdf.deriveMachineFileKey(licenseKey, fingerprint);
    byte[] json = CheckoutFixture.machinePayloadJson(fingerprint);
    String enc = CheckoutFixture.encryptedEnc(json, aesKey);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "aes-256-gcm+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = signingKey.generatePublicKey().getEncoded();
    Machine machine = file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey, licenseKey,
        fingerprint);

    assertThat(machine.id()).isEqualTo("mach_123");
    assertThat(machine.fingerprint()).isEqualTo(fingerprint);
  }

  /**
   * GOTCHA regression: machine-file decryption binds BOTH the license key AND the fingerprint via
   * HKDF's {@code info} parameter -- a correct license key with the wrong fingerprint must still
   * fail closed.
   */
  @Test
  void verifyAndDecryptThrowsDecryptionExceptionForWrongFingerprintOnEncryptedFile() {
    Ed25519PrivateKeyParameters signingKey = generateEd25519Key();
    String licenseKey = "TAMGA-LICENSE-KEY";
    String realFingerprint = "real-fingerprint";
    byte[] aesKey = Hkdf.deriveMachineFileKey(licenseKey, realFingerprint);
    byte[] json = CheckoutFixture.machinePayloadJson(realFingerprint);
    String enc = CheckoutFixture.encryptedEnc(json, aesKey);
    String sig = CheckoutFixture.ed25519Sign(enc, signingKey);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "aes-256-gcm+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = signingKey.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey,
        licenseKey, "wrong-fingerprint"))
        .isInstanceOf(TamgaCheckoutException.DecryptionException.class);
  }

  @Test
  void parseThrowsForMissingBeginMarker() {
    assertThatThrownBy(() -> MachineFile.parse("not a pem file\n-----END MACHINE FILE-----"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMalformedBase64Body() {
    String pem = "-----BEGIN MACHINE FILE-----\nnot valid base64!!!\n-----END MACHINE FILE-----";

    assertThatThrownBy(() -> MachineFile.parse(pem))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void parseThrowsForMalformedCertificateJson() {
    byte[] notCertificate = "not a certificate".getBytes(StandardCharsets.UTF_8);
    String body = Base64.getEncoder().encodeToString(notCertificate);
    String pem = "-----BEGIN MACHINE FILE-----\n" + body + "\n-----END MACHINE FILE-----";

    assertThatThrownBy(() -> MachineFile.parse(pem))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void verifyReturnsFalseForMalformedBase64Signature() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    String pem = CheckoutFixture.wrapMachinePem("AA==", "not valid base64!!!", "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThat(file.verify(LicenseScheme.ED25519_SIGN, publicKey)).isFalse();
  }

  @Test
  void verifyAndDecryptThrowsForMalformedEncBase64() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    String enc = "not valid base64!!!";
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey,
        "unused", "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void verifyAndDecryptThrowsForMalformedPayloadJson() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    byte[] notResourceJson = "not resource json".getBytes(StandardCharsets.UTF_8);
    String enc = Base64.getEncoder().encodeToString(notResourceJson);
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "base64+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey,
        "unused", "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void verifyAndDecryptThrowsForShortEncryptedPayload() {
    Ed25519PrivateKeyParameters key = generateEd25519Key();
    String enc = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    String sig = CheckoutFixture.ed25519Sign(enc, key);
    String pem = CheckoutFixture.wrapMachinePem(enc, sig, "aes-256-gcm+ed25519");

    MachineFile file = MachineFile.parse(pem);
    byte[] publicKey = key.generatePublicKey().getEncoded();

    assertThatThrownBy(() -> file.verifyAndDecrypt(LicenseScheme.ED25519_SIGN, publicKey,
        "unused", "unused"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void validateTtlAcceptsValuesWithinValidRange() {
    MachineFile.validateTtl(1);
    MachineFile.validateTtl(MachineFile.MAX_TTL_SECONDS);
  }

  @Test
  void validateTtlRejectsZeroNegativeAndOverMaxValues() {
    int[] invalidValues = {0, -1, MachineFile.MAX_TTL_SECONDS + 1};
    for (int invalid : invalidValues) {
      assertThatThrownBy(() -> MachineFile.validateTtl(invalid))
          .isInstanceOf(TamgaCheckoutException.TtlInvalidException.class);
    }
  }
}
