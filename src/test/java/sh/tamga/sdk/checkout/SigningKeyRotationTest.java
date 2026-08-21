package sh.tamga.sdk.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.crypto.Hkdf;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.LicenseScheme;
import sh.tamga.sdk.model.SigningKey;
import sh.tamga.sdk.support.CheckoutFixture;

/**
 * The defect this whole path exists to close: an offline file signed BEFORE the account rotated
 * its Ed25519 signing key is authentic, and against a single embedded key it fails with exactly
 * the error a forgery produces -- so a paying customer holding a valid file is locked out and the
 * error sends support to the wrong place.
 */
class SigningKeyRotationTest {

  private static final String LICENSE_KEY = "TAMGA-ROTATION-TEST";
  private static final String FINGERPRINT = "fp-rotation";

  private static Ed25519PrivateKeyParameters generateKey() {
    return new Ed25519PrivateKeyParameters(new SecureRandom());
  }

  private static String publicKeyBase64(Ed25519PrivateKeyParameters key) {
    return Base64.getEncoder().encodeToString(key.generatePublicKey().getEncoded());
  }

  private static SigningKey resource(String publicKey, String status) throws IOException {
    String json = "{\"type\":\"signing-keys\",\"id\":\"" + Ed25519.keyId(publicKey)
        + "\",\"attributes\":{\"algorithm\":\"ed25519\",\"publicKey\":\"" + publicKey
        + "\",\"status\":\"" + status + "\",\"created\":\"2026-01-01T00:00:00Z\"}}";
    return SigningKey.fromResourceNode(new ObjectMapper().readTree(json));
  }

  private static String licensePem(Ed25519PrivateKeyParameters signer, String keyId) {
    return licensePem(signer, keyId, null);
  }

  private static String licensePem(Ed25519PrivateKeyParameters signer, String keyId, Long exp) {
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, exp, keyId);
    String enc = CheckoutFixture.plainEnc(json);
    return CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+ed25519+v2");
  }

  private static String machinePem(Ed25519PrivateKeyParameters signer, String keyId) {
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, null, keyId);
    String enc = CheckoutFixture.plainEnc(json);
    return CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+ed25519+v2");
  }

  // ------------------------------------------------------------- the defect

  @Test
  void fileSignedBeforeTheRotationStillVerifiesAgainstTheRetiredKey() throws IOException {
    // The whole point of the endpoint: retired keys are published so an old file keeps verifying.
    Ed25519PrivateKeyParameters retired = generateKey();
    Ed25519PrivateKeyParameters current = generateKey();
    SigningKeySet keys = SigningKeySet.of(Arrays.asList(
        resource(publicKeyBase64(current), "active"),
        resource(publicKeyBase64(retired), "retired")));
    String pem = licensePem(retired, Ed25519.keyId(publicKeyBase64(retired)));

    VerifiedLicenseFile verified =
        LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L);

    assertThat(verified.license().id()).isEqualTo("lic_123");
    assertThat(verified.claims().keyId()).isEqualTo(Ed25519.keyId(publicKeyBase64(retired)));
    // The key that actually verified comes back, so a caller can see the file predates the
    // rotation and is due a fresh checkout even though nothing is wrong with it.
    assertThat(verified.key().isRetired()).isTrue();
    assertThat(verified.key().keyId()).isEqualTo(Ed25519.keyId(publicKeyBase64(retired)));
  }

  @Test
  void singleKeyPathStillReportsTheSameFileAsForged() {
    // The behaviour being fixed, pinned so the contrast is explicit rather than asserted in prose:
    // against the current key alone, an authentic pre-rotation file is indistinguishable from a
    // forgery.
    Ed25519PrivateKeyParameters retired = generateKey();
    Ed25519PrivateKeyParameters current = generateKey();
    String pem = licensePem(retired, Ed25519.keyId(publicKeyBase64(retired)));

    LicenseFile file = LicenseFile.parse(pem);

    assertThat(file.verify(current.generatePublicKey().getEncoded())).isFalse();
    assertThatThrownBy(() -> file.verifyAndDecrypt(current.generatePublicKey().getEncoded(),
        LICENSE_KEY))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  // -------------------------------------------- the two distinct outcomes

  @Test
  void keyIdTheSetDoesNotHoldIsNotReportedAsForgery() {
    Ed25519PrivateKeyParameters missing = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(held));
    String claimed = Ed25519.keyId(publicKeyBase64(missing));
    String pem = licensePem(missing, claimed);

    assertThatThrownBy(
        () -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L))
        .isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class)
        .isNotInstanceOf(TamgaCheckoutException.SignatureVerificationException.class)
        .satisfies(e -> {
          TamgaCheckoutException.UnknownSigningKeyException unknown =
              (TamgaCheckoutException.UnknownSigningKeyException) e;
          assertThat(unknown.keyId()).isEqualTo(claimed);
          assertThat(unknown.availableKeyIds())
              .containsExactly(Ed25519.keyId(publicKeyBase64(held)));
        });
  }

  @Test
  void tamperedFileNamingHeldKeyStaysSignatureFailure() {
    // The other half of the distinction: the named key is right here and the signature still
    // fails. That is tampering, and reporting it as a stale key set would be the worse mistake.
    Ed25519PrivateKeyParameters key = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null,
        Ed25519.keyId(publicKeyBase64(key)));
    String enc = CheckoutFixture.plainEnc(json);
    String forgedSignature = Base64.getEncoder().encodeToString(new byte[64]);
    String pem = CheckoutFixture.wrapLicensePem(enc, forgedSignature, "base64+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(key));

    assertThatThrownBy(
        () -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class)
        .isNotInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class);
  }

  @Test
  void fileThatNamesNoKeyAtAllIsSignatureFailure() {
    // No claim to act on, so there is nothing that distinguishes it from a forgery.
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(held));
    String pem = licensePem(signer, null);

    assertThatThrownBy(
        () -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void anEmptyKeySetReportsNoUsableKeyRatherThanAnUnknownOne() {
    Ed25519PrivateKeyParameters signer = generateKey();
    String pem = licensePem(signer, Ed25519.keyId(publicKeyBase64(signer)));

    assertThatThrownBy(() -> LicenseFile.parse(pem)
        .verifyWithClaims(SigningKeySet.empty(), LICENSE_KEY, 1_767_225_600L))
        .isInstanceOf(TamgaCheckoutException.NoUsableSigningKeyException.class);
    // The cast is needed because a bare null literal matches both the byte[] and the
    // SigningKeySet overload -- the one call shape the addition makes ambiguous, and only for a
    // call that was already meaningless.
    assertThatThrownBy(() -> LicenseFile.parse(pem)
        .verifyWithClaims((SigningKeySet) null, LICENSE_KEY, 1_767_225_600L))
        .isInstanceOf(TamgaCheckoutException.NoUsableSigningKeyException.class);
  }

  @Test
  void setWhoseOnlyKeysWereUnusableNamesThemInTheError() {
    // Distinct from an empty set only in the diagnostics: the caller fetched keys and every one of
    // them was dropped, which is worth saying out loud next to the failure.
    Ed25519PrivateKeyParameters signer = generateKey();
    String pem = licensePem(signer, Ed25519.keyId(publicKeyBase64(signer)));
    SigningKeySet keys = SigningKeySet.of(Collections.singletonList(
        SigningKey.ed25519("0000000000000000", "!!!not base64!!!")));

    assertThatThrownBy(() -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1L))
        .isInstanceOf(TamgaCheckoutException.NoUsableSigningKeyException.class)
        .satisfies(e -> assertThat(
            ((TamgaCheckoutException.NoUsableSigningKeyException) e).presentKeyIds())
            .containsExactly("0000000000000000"));
  }

  // ---------------------------------------- the unpublished-key-column case

  @Test
  void theUnpublishedAccountSentinelIsItsOwnDistinguishableCondition() {
    // key_id("") -- what an account whose ed25519_public_key column was never populated stamps on
    // every file it signs. No key set can ever hold this id, so "refetch the keys" is the wrong
    // advice and this is not an ordinary stale-set failure.
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(held));
    String pem = licensePem(signer, Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID);

    assertThatThrownBy(() -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1L))
        .isInstanceOf(TamgaCheckoutException.SigningKeyNotPublishedException.class)
        // Still an unknown-key failure for a caller who only cares that it is not a forgery.
        .isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class)
        .hasMessageContaining("no published Ed25519 public key");
  }

  @Test
  void fileNamingTheUnpublishedSentinelStillVerifiesWhenTheRealKeyIsHeld() {
    // The payoff of checking signatures before reading the claim. Such a file IS signed by a real
    // private key -- only the kid names nothing -- so selecting a key by kid first would refuse it
    // even with the right key in hand.
    Ed25519PrivateKeyParameters signer = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));
    String pem = licensePem(signer, Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID);

    VerifiedLicenseFile verified =
        LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L);

    assertThat(verified.license().id()).isEqualTo("lic_123");
    assertThat(verified.claims().keyId()).isEqualTo(Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID);
  }

  @Test
  void mislabelledKeyStillVerifiesItsOwnFilesDespiteTheStrictLookup() {
    // What the strict served-id lookup does NOT cost, asserted against a real file rather than
    // against a map lookup. The account's published row carries the wrong id -- a server-side
    // fault a client cannot fix -- while the file's kid names the id the key really has. Keys are
    // tried against the signature before any id is consulted, so this still verifies, and the
    // served-id rule only decides which error a file that verified under NO key would report.
    //
    // The version of this test in SigningKeySetTest asserted a lookup and never reached
    // verifyWithClaims, so it stayed green under the kid-first mutation it names. This one dies
    // under it: kid-first would look for "deadbeefdeadbeef", find no entry named by the file's
    // kid, and refuse a file that is genuinely signed by a key in hand.
    Ed25519PrivateKeyParameters signer = generateKey();
    String realKeyId = Ed25519.keyId(publicKeyBase64(signer));
    SigningKeySet keys = SigningKeySet.of(Collections.singletonList(
        SigningKey.ed25519("deadbeefdeadbeef", publicKeyBase64(signer))));
    String pem = licensePem(signer, realKeyId);

    VerifiedLicenseFile verified =
        LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L);

    assertThat(verified.license().id()).isEqualTo("lic_123");
    assertThat(verified.claims().keyId()).isEqualTo(realKeyId);
    // The entry that verified is the mislabelled one, reported under the id the server served.
    assertThat(verified.key().keyId()).isEqualTo("deadbeefdeadbeef");
    assertThat(keys.mismatchedKeyIds()).containsExactly("deadbeefdeadbeef");
  }

  // ------------------------------------------------------ encrypted files

  @Test
  void encryptedLicenseFileVerifiesAndDecryptsThroughKeySet() {
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null,
        Ed25519.keyId(publicKeyBase64(signer)));
    String enc = CheckoutFixture.encryptedEnc(json, Hkdf.deriveLicenseFileKey(LICENSE_KEY));
    String pem = CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "aes-256-gcm+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));

    VerifiedLicenseFile verified =
        LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_600L);

    assertThat(verified.license().key()).isEqualTo(LICENSE_KEY);
  }

  @Test
  void anEncryptedFileWithTheWrongLicenseKeyFailsAtDecryptionNotVerification() {
    // The signature covers enc's base64 string, so it verifies before the license key is used at
    // all. Keeping the two distinct is what lets a caller say "check your license key" instead of
    // "this file may be forged".
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null,
        Ed25519.keyId(publicKeyBase64(signer)));
    String enc = CheckoutFixture.encryptedEnc(json, Hkdf.deriveLicenseFileKey(LICENSE_KEY));
    String pem = CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "aes-256-gcm+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));

    assertThatThrownBy(() -> LicenseFile.parse(pem).verifyWithClaims(keys, "WRONG-KEY", 1L))
        .isInstanceOf(TamgaCheckoutException.DecryptionException.class);
  }

  @Test
  void unreadableClaimOnUnverifiableFileDegradesToSignatureFailure() {
    // The kid probe runs on bytes nothing vouched for, and here it cannot even be decrypted. The
    // honest answer is the plain signature failure, not a guess about which key was meant.
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null,
        Ed25519.keyId(publicKeyBase64(signer)));
    String enc = CheckoutFixture.encryptedEnc(json, Hkdf.deriveLicenseFileKey(LICENSE_KEY));
    String pem = CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "aes-256-gcm+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(held));

    assertThatThrownBy(() -> LicenseFile.parse(pem).verifyWithClaims(keys, "WRONG-KEY", 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  // -------------------------------------------------- claims still enforced

  @Test
  void theSignedExpiryIsEnforcedOnTheKeySetPathToo() {
    Ed25519PrivateKeyParameters signer = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));
    String pem = licensePem(signer, Ed25519.keyId(publicKeyBase64(signer)), 1_767_225_600L);

    assertThatThrownBy(
        () -> LicenseFile.parse(pem).verifyWithClaims(keys, LICENSE_KEY, 1_767_225_700L))
        .isInstanceOf(TamgaCheckoutException.LicenseFileExpiredException.class);
  }

  @Test
  void ttlLessFileVerifiesThroughTheWallClockEntryPoint() {
    Ed25519PrivateKeyParameters signer = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));
    String pem = licensePem(signer, Ed25519.keyId(publicKeyBase64(signer)));

    VerifiedLicenseFile verified = LicenseFile.parse(pem).verifyAndDecrypt(keys, LICENSE_KEY);

    assertThat(verified.license().id()).isEqualTo("lic_123");
    assertThat(verified.claims().expiresAt()).isNull();
  }

  @Test
  void anUnsupportedAlgIsRejectedBeforeAnyKeyIsTried() {
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null, "whatever");
    String enc = CheckoutFixture.plainEnc(json);
    String pem = CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+ed25519+v1");

    assertThatThrownBy(() -> LicenseFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(signer)), LICENSE_KEY, 1L))
        .isInstanceOf(TamgaCheckoutException.UnsupportedAlgorithmException.class);
  }

  // ------------------------------------------------------- machine files

  @Test
  void machineFileSignedBeforeRotationVerifiesAgainstTheRetiredKey() throws IOException {
    Ed25519PrivateKeyParameters retired = generateKey();
    Ed25519PrivateKeyParameters current = generateKey();
    SigningKeySet keys = SigningKeySet.of(Arrays.asList(
        resource(publicKeyBase64(current), "active"),
        resource(publicKeyBase64(retired), "retired")));
    String pem = machinePem(retired, Ed25519.keyId(publicKeyBase64(retired)));

    VerifiedMachineFile verified = MachineFile.parse(pem)
        .verifyWithClaims(keys, LICENSE_KEY, FINGERPRINT, 1_767_225_600L);

    assertThat(verified.machine().fingerprint()).isEqualTo(FINGERPRINT);
    assertThat(verified.key().isRetired()).isTrue();
  }

  @Test
  void encryptedMachineFileVerifiesAndDecryptsThroughKeySet() {
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, null,
        Ed25519.keyId(publicKeyBase64(signer)));
    String enc = CheckoutFixture.machineEncryptedEnc(json,
        Hkdf.deriveMachineFileKey(LICENSE_KEY, FINGERPRINT));
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "aes-256-gcm+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));

    VerifiedMachineFile verified = MachineFile.parse(pem)
        .verifyAndDecrypt(keys, LICENSE_KEY, FINGERPRINT);

    assertThat(verified.machine().fingerprint()).isEqualTo(FINGERPRINT);
    assertThat(verified.claims().keyId()).isEqualTo(Ed25519.keyId(publicKeyBase64(signer)));
  }

  @Test
  void unknownKeyIdOnMachineFileIsNotReportedAsForgery() {
    Ed25519PrivateKeyParameters missing = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(held));
    String pem = machinePem(missing, "0f0f0f0f0f0f0f0f");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(keys, LICENSE_KEY, FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class)
        .satisfies(e -> assertThat(
            ((TamgaCheckoutException.UnknownSigningKeyException) e).keyId())
            .isEqualTo("0f0f0f0f0f0f0f0f"));
  }

  @Test
  void nonEd25519MachineFileIsRefusedByTheKeySetPath() {
    // A key set holds Ed25519 keys only, and an RSA- or ECDSA-signed file's kid names the
    // account's Ed25519 key rather than the key that signed it -- matching on it would be worse
    // than useless. Refuse rather than pretend.
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, null, "0f0f0f0f0f0f0f0f");
    String enc = CheckoutFixture.plainEnc(json);
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+rsa-sha256+v2");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(signer)), LICENSE_KEY,
            FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.UnsupportedAlgorithmException.class)
        .hasMessageContaining("Ed25519 keys only");
  }

  @Test
  void theSchemeAwareMachinePathIsUnchanged() {
    Ed25519PrivateKeyParameters signer = generateKey();
    String pem = machinePem(signer, Ed25519.keyId(publicKeyBase64(signer)));

    assertThat(MachineFile.parse(pem)
        .verify(LicenseScheme.ED25519_SIGN, signer.generatePublicKey().getEncoded())).isTrue();
  }

  @Test
  void theSignedExpiryIsEnforcedOnTheMachineKeySetPathToo() {
    Ed25519PrivateKeyParameters signer = generateKey();
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, 1_767_225_600L,
        Ed25519.keyId(publicKeyBase64(signer)));
    String enc = CheckoutFixture.plainEnc(json);
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+ed25519+v2");
    SigningKeySet keys = SigningKeySet.ofPublicKeys(publicKeyBase64(signer));

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(keys, LICENSE_KEY, FINGERPRINT, 1_767_225_700L))
        .isInstanceOf(TamgaCheckoutException.LicenseFileExpiredException.class);
  }

  @Test
  void licenseFileWhoseEncIsNotBase64FallsBackToTheSignatureFailure() {
    // The kid probe cannot even decode the payload, so there is nothing to tell the caller beyond
    // "this did not verify". Guessing at a key set problem here would be an invention.
    Ed25519PrivateKeyParameters held = generateKey();
    String pem = CheckoutFixture.wrapLicensePem("!!!not base64!!!",
        Base64.getEncoder().encodeToString(new byte[64]), "base64+ed25519+v2");

    assertThatThrownBy(() -> LicenseFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY, 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void encryptedLicenseFileSignedByAnAbsentKeyIsStillDistinguishable() {
    // The probe has to decrypt to read the claim here, and it can: the license key is right. So a
    // rotated-away key stays distinguishable even when the payload is encrypted.
    Ed25519PrivateKeyParameters missing = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    byte[] json = CheckoutFixture.licensePayloadJson(LICENSE_KEY, null,
        Ed25519.keyId(publicKeyBase64(missing)));
    String enc = CheckoutFixture.encryptedEnc(json, Hkdf.deriveLicenseFileKey(LICENSE_KEY));
    String pem = CheckoutFixture.wrapLicensePem(enc, CheckoutFixture.ed25519Sign(enc, missing),
        "aes-256-gcm+ed25519+v2");

    assertThatThrownBy(() -> LicenseFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY, 1L))
        .isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class)
        .satisfies(e -> assertThat(
            ((TamgaCheckoutException.UnknownSigningKeyException) e).keyId())
            .isEqualTo(Ed25519.keyId(publicKeyBase64(missing))));
  }

  @Test
  void machineFileWhoseEncIsNotBase64FallsBackToTheSignatureFailure() {
    Ed25519PrivateKeyParameters held = generateKey();
    String pem = CheckoutFixture.wrapMachinePem("!!!not base64!!!",
        Base64.getEncoder().encodeToString(new byte[64]), "base64+ed25519+v2");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY,
            FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void machineFileWithNoKeyIdClaimFallsBackToTheSignatureFailure() {
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    String pem = machinePem(signer, null);

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY,
            FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void machineFileWhosePayloadIsNotJsonFallsBackToTheSignatureFailure() {
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    String enc = CheckoutFixture.plainEnc("not json at all".getBytes(StandardCharsets.UTF_8));
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "base64+ed25519+v2");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY,
            FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }

  @Test
  void encryptedMachineFileSignedByAnAbsentKeyIsStillDistinguishable() {
    Ed25519PrivateKeyParameters missing = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, null, "0f0f0f0f0f0f0f0f");
    String enc = CheckoutFixture.machineEncryptedEnc(json,
        Hkdf.deriveMachineFileKey(LICENSE_KEY, FINGERPRINT));
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, missing),
        "aes-256-gcm+ed25519+v2");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY,
            FINGERPRINT, 1L))
        .isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class);
  }

  @Test
  void encryptedMachineFileWithTheWrongFingerprintFallsBackToTheSignatureFailure() {
    // The probe cannot decrypt without the right fingerprint, so it reports nothing and the plain
    // signature failure stands.
    Ed25519PrivateKeyParameters signer = generateKey();
    Ed25519PrivateKeyParameters held = generateKey();
    byte[] json = CheckoutFixture.machinePayloadJson(FINGERPRINT, null, "0f0f0f0f0f0f0f0f");
    String enc = CheckoutFixture.machineEncryptedEnc(json,
        Hkdf.deriveMachineFileKey(LICENSE_KEY, FINGERPRINT));
    String pem = CheckoutFixture.wrapMachinePem(enc, CheckoutFixture.ed25519Sign(enc, signer),
        "aes-256-gcm+ed25519+v2");

    assertThatThrownBy(() -> MachineFile.parse(pem)
        .verifyWithClaims(SigningKeySet.ofPublicKeys(publicKeyBase64(held)), LICENSE_KEY,
            "wrong-fingerprint", 1L))
        .isInstanceOf(TamgaCheckoutException.SignatureVerificationException.class);
  }
}
