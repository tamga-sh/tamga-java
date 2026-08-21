package sh.tamga.sdk.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.SigningKey;

class SigningKeySetTest {

  /** Base64 of 32 zero bytes -- a well-formed key encoding, used here for its id alone. */
  private static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final String ZERO_KEY_ID = "51643eac9777b63a";
  private static final String SEQUENTIAL_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  private static SigningKey resource(String id, String algorithm, String publicKey, String status)
      throws IOException {
    String json = "{\"type\":\"signing-keys\",\"id\":\"" + id + "\",\"attributes\":{"
        + "\"algorithm\":\"" + algorithm + "\",\"publicKey\":\"" + publicKey + "\","
        + "\"status\":\"" + status + "\",\"created\":\"2026-01-01T00:00:00Z\"}}";
    return SigningKey.fromResourceNode(new ObjectMapper().readTree(json));
  }

  @Test
  void pinnedKeyIndexesItselfUnderItsComputedKeyId() {
    SigningKeySet set = SigningKeySet.ofPublicKeys(ZERO_KEY);

    assertThat(set.size()).isEqualTo(1);
    assertThat(set.isEmpty()).isFalse();
    assertThat(set.contains(ZERO_KEY_ID)).isTrue();
    assertThat(set.keyIds()).containsExactly(ZERO_KEY_ID);
    assertThat(set.skippedKeyIds()).isEmpty();
    assertThat(set.mismatchedKeyIds()).isEmpty();
  }

  @Test
  void mistypedPinnedKeyFailsLoudlyRatherThanSilently() {
    // Skipping it would produce a set that reports every genuine file as signed by an unknown
    // key, at runtime, in the field. A typo in a pinned constant must fail at startup.
    assertThatThrownBy(() -> SigningKeySet.ofPublicKeys("not base64 at all"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
    assertThatThrownBy(() -> SigningKeySet.ofPublicKeys("QUJD"))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class)
        .hasMessageContaining("32-byte");
    assertThatThrownBy(() -> SigningKeySet.ofPublicKeys(Collections.singletonList((String) null)))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void fetchedKeySetTakesTheKeyIdFromTheResourceIdNotFromLocalHash() throws IOException {
    // The server's `id` IS the kid (accounts/serializer.rs:123, documented at :103): nothing is
    // path, and indexing by a locally computed value instead would silently disagree with the
    // server the moment the two ever differ.
    SigningKeySet set = SigningKeySet.of(
        Collections.singletonList(resource("deadbeefdeadbeef", "ed25519", ZERO_KEY, "retired")));

    assertThat(set.keyIds()).containsExactly("deadbeefdeadbeef");
    assertThat(set.contains("deadbeefdeadbeef")).isTrue();
  }

  @Test
  void servedKeyIdThatDisagreesWithTheLocalComputationIsReportedNotHidden() throws IOException {
    // The local computation is a cross-check, and a mismatch is a server-side fault no client can
    // fix -- so it is surfaced as its own condition rather than absorbed by a fallback lookup.
    // Matching the computed id as a second spelling would invent a rule the wire does not have and
    // hide exactly the signal an operator needs.
    SigningKeySet set = SigningKeySet.of(
        Collections.singletonList(resource("deadbeefdeadbeef", "ed25519", ZERO_KEY, "active")));

    assertThat(set.mismatchedKeyIds()).containsExactly("deadbeefdeadbeef");
    assertThat(set.contains("deadbeefdeadbeef")).isTrue();
    // The SERVED id only: the file's kid is drawn from the same column this id comes from.
    assertThat(set.contains(Ed25519.keyId(ZERO_KEY))).isFalse();
  }

  @Test
  void findReturnsTheKeyBehindMislabelledServedIds() throws IOException {
    // Renamed. This was called mislabelledKeyStillVerifiesItsOwnFilesDespiteTheStrictLookup and
    // claimed to cover the verification path, which it never reaches -- it looks a key up in a set
    // and nothing signs or verifies anything. Measured: the kid-first mutation that claim exists
    // to catch left it green. The claim now lives in SigningKeyRotationTest, against a real file,
    // where it dies under that mutation. What is left here is what this test actually did: the
    // entry behind a served id is retrievable and carries the key bytes, mislabelled or not.
    SigningKeySet set = SigningKeySet.of(
        Collections.singletonList(resource("deadbeefdeadbeef", "ed25519", ZERO_KEY, "active")));

    assertThat(set.entries()).hasSize(1);
    assertThat(set.find("deadbeefdeadbeef").publicKey())
        .isEqualTo(java.util.Base64.getDecoder().decode(ZERO_KEY));
  }

  @Test
  void consistentlyLabelledKeySetReportsNoMismatch() throws IOException {
    SigningKeySet set = SigningKeySet.of(
        Collections.singletonList(resource(ZERO_KEY_ID, "ed25519", ZERO_KEY, "active")));

    assertThat(set.mismatchedKeyIds()).isEmpty();
  }

  @Test
  void oneUnusablePublishedKeyDoesNotStrandTheOthers() throws IOException {
    // A future algorithm and a key that does not decode are skipped; the Ed25519 rows around them
    // still verify their files. Failing the whole set would strand every file the account signed.
    SigningKeySet set = SigningKeySet.of(Arrays.asList(
        resource("0000000000000000", "ml-dsa-44", ZERO_KEY, "active"),
        resource("1111111111111111", "ed25519", "!!!not base64!!!", "active"),
        resource("2222222222222222", "ed25519", ZERO_KEY, "retired")));

    assertThat(set.size()).isEqualTo(1);
    assertThat(set.contains("2222222222222222")).isTrue();
    assertThat(set.contains("0000000000000000")).isFalse();
    assertThat(set.contains("1111111111111111")).isFalse();
    assertThat(set.skippedKeyIds())
        .containsExactly("0000000000000000", "1111111111111111");
  }

  @Test
  void keyOfTheWrongLengthIsSkippedEvenWhenItDecodes() throws IOException {
    // 32 bytes exactly. A 31- or 33-byte key is not an Ed25519 public key, and letting it into the
    // set only produces a candidate that can never verify anything.
    SigningKeySet set = SigningKeySet.of(
        Collections.singletonList(resource("3333333333333333", "ed25519", "QUJD", "active")));

    assertThat(set.isEmpty()).isTrue();
    assertThat(set.skippedKeyIds()).containsExactly("3333333333333333");
  }

  @Test
  void keyIdMatchingIsExactAndCaseSensitive() {
    SigningKeySet set = SigningKeySet.ofPublicKeys(ZERO_KEY);

    assertThat(set.contains(ZERO_KEY_ID.toUpperCase(java.util.Locale.ROOT))).isFalse();
    assertThat(set.contains("51643eac9777b63")).isFalse();
    assertThat(set.contains("")).isFalse();
    assertThat(set.contains(null)).isFalse();
  }

  @Test
  void anEmptySetIsBuildableAndFindsNothing() {
    assertThat(SigningKeySet.empty().isEmpty()).isTrue();
    assertThat(SigningKeySet.empty().size()).isZero();
    assertThat(SigningKeySet.empty().contains(ZERO_KEY_ID)).isFalse();
    assertThat(SigningKeySet.of(null).isEmpty()).isTrue();
    assertThat(SigningKeySet.of(Collections.<SigningKey>emptyList()).isEmpty()).isTrue();
    assertThat(SigningKeySet.ofPublicKeys((String[]) null).isEmpty()).isTrue();
    assertThat(SigningKeySet.ofPublicKeys((java.util.Collection<String>) null).isEmpty()).isTrue();
  }

  @Test
  void resourceMissingItsIdOrPublicKeyIsSkippedRatherThanCrashing() throws IOException {
    SigningKey noPublicKey = SigningKey.fromResourceNode(new ObjectMapper().readTree(
        "{\"type\":\"signing-keys\",\"id\":\"4444444444444444\",\"attributes\":{"
            + "\"algorithm\":\"ed25519\",\"status\":\"active\"}}"));
    SigningKey noId = SigningKey.fromResourceNode(new ObjectMapper().readTree(
        "{\"type\":\"signing-keys\",\"attributes\":{\"algorithm\":\"ed25519\","
            + "\"publicKey\":\"" + ZERO_KEY + "\",\"status\":\"active\"}}"));

    SigningKeySet set = SigningKeySet.of(Arrays.asList(noPublicKey, noId, null));

    assertThat(set.isEmpty()).isTrue();
    assertThat(set.skippedKeyIds()).containsExactly("4444444444444444", "null", "(null)");
  }

  @Test
  void theSetHoldsEveryKeyItWasGivenInOrder() {
    SigningKeySet set = SigningKeySet.ofPublicKeys(ZERO_KEY, SEQUENTIAL_KEY);

    assertThat(set.keyIds()).containsExactly(ZERO_KEY_ID, Ed25519.keyId(SEQUENTIAL_KEY));
    assertThat(set.size()).isEqualTo(2);
  }

  @Test
  void theSetIsImmutableFromOutside() {
    SigningKeySet set = SigningKeySet.ofPublicKeys(ZERO_KEY);

    assertThatThrownBy(() -> set.keyIds().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.skippedKeyIds().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.mismatchedKeyIds().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void handingOutKeyBytesCannotMutateTheSet() {
    // find() is package-private and hands back a copy: a verifier that scribbled on the array it
    // was given must not be able to corrupt every later verification through the same set.
    SigningKeySet set = SigningKeySet.ofPublicKeys(ZERO_KEY);
    SigningKeySet.Entry entry = set.find(ZERO_KEY_ID);

    byte[] first = entry.publicKey();
    Arrays.fill(first, (byte) 0x7f);

    assertThat(entry.publicKey()).containsOnly((byte) 0);
  }
}
