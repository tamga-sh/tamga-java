package sh.tamga.sdk.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The three signing-key conditions, and what a caller can read off each. */
class SigningKeyExceptionTest {

  @Test
  void unknownKeyCarriesTheClaimedIdAndWhatTheSetHeld() {
    TamgaCheckoutException.UnknownSigningKeyException error =
        new TamgaCheckoutException.UnknownSigningKeyException("0f0f0f0f0f0f0f0f",
            Arrays.asList("1111111111111111", "2222222222222222"));

    assertThat(error.keyId()).isEqualTo("0f0f0f0f0f0f0f0f");
    assertThat(error.availableKeyIds())
        .containsExactly("1111111111111111", "2222222222222222");
    assertThat(error.getMessage()).contains("0f0f0f0f0f0f0f0f")
        .contains("1111111111111111, 2222222222222222")
        .contains("key rotation");
  }

  @Test
  void unknownKeyDegradesGracefullyWhenNothingWasHeld() {
    TamgaCheckoutException.UnknownSigningKeyException nulls =
        new TamgaCheckoutException.UnknownSigningKeyException("0f0f0f0f0f0f0f0f", null);
    TamgaCheckoutException.UnknownSigningKeyException empty =
        new TamgaCheckoutException.UnknownSigningKeyException("0f0f0f0f0f0f0f0f",
            Collections.<String>emptyList());

    assertThat(nulls.availableKeyIds()).isEmpty();
    assertThat(nulls.getMessage()).contains("holding: none");
    assertThat(empty.getMessage()).contains("holding: none");
  }

  @Test
  void theAvailableListCannotBeMutatedThroughTheException() {
    TamgaCheckoutException.UnknownSigningKeyException error =
        new TamgaCheckoutException.UnknownSigningKeyException("0f0f0f0f0f0f0f0f",
            Collections.singletonList("1111111111111111"));

    assertThatThrownBy(() -> error.availableKeyIds().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theUnpublishedSentinelIsCaughtByEitherType() {
    // Deliberately a subclass: a caller who only wants "not a forgery" keeps one catch, while a
    // caller who has to tell support which of the two it is can catch the narrower type.
    TamgaCheckoutException.SigningKeyNotPublishedException error =
        new TamgaCheckoutException.SigningKeyNotPublishedException("e3b0c44298fc1c14",
            Collections.singletonList("1111111111111111"));

    assertThat(error).isInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class);
    assertThat(error).isInstanceOf(TamgaCheckoutException.class);
    assertThat(error.keyId()).isEqualTo("e3b0c44298fc1c14");
    assertThat(error.availableKeyIds()).containsExactly("1111111111111111");
    assertThat(error.getMessage()).contains("EMPTY public key")
        .contains("will not help");
  }

  @Test
  void noUsableKeyNamesWhatWasPresentButUnusable() {
    TamgaCheckoutException.NoUsableSigningKeyException listed =
        new TamgaCheckoutException.NoUsableSigningKeyException(
            Arrays.asList("1111111111111111", "2222222222222222"));
    TamgaCheckoutException.NoUsableSigningKeyException emptySet =
        new TamgaCheckoutException.NoUsableSigningKeyException(Collections.<String>emptyList());
    TamgaCheckoutException.NoUsableSigningKeyException nullSet =
        new TamgaCheckoutException.NoUsableSigningKeyException(null);

    assertThat(listed.presentKeyIds())
        .containsExactly("1111111111111111", "2222222222222222");
    assertThat(listed.getMessage()).contains("present but unusable");
    assertThat(emptySet.getMessage()).contains("it is empty");
    assertThat(nullSet.presentKeyIds()).isEmpty();
    assertThat(nullSet.getMessage()).contains("it is empty");
    // Not an unknown-key failure: no file could have verified, so the file's own claim is
    // irrelevant to it.
    assertThat(nullSet).isNotInstanceOf(TamgaCheckoutException.UnknownSigningKeyException.class);
  }
}
