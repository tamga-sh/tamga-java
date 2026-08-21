package sh.tamga.sdk.checkout;

import java.util.function.Predicate;
import java.util.function.Supplier;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.error.TamgaCheckoutException;

/**
 * Picks the key an offline file was actually signed with out of the set of keys the caller trusts.
 *
 * <p><b>Order, and why it is this way round.</b> The obvious implementation reads the file's
 * {@code kid} claim first and looks a key up by it. That inverts the one ordering rule this
 * package is built on -- verify the signature before interpreting anything inside {@code enc} --
 * because the claim lives INSIDE the signed (and possibly encrypted) payload, so reading it means
 * parsing attacker-supplied bytes before anything has vouched for them.
 *
 * <p>So this does the opposite. Every candidate key is tried against the signature first and the
 * happy path never touches the payload unverified. The claim is read only after every key has
 * failed -- at which point the file is already known not to be authentic under anything held, and
 * the only remaining question is which of two errors to report. Its value picks an error label and
 * is used for nothing else.
 *
 * <p>The cost is at most one signature check per key the account has ever held, which for Ed25519
 * is microseconds and for a realistic key set is a handful of them. {@code kid} is an
 * unauthenticated hint in JWS too, for the same reason: it selects a key from a trusted set, it
 * never establishes trust.
 */
final class SigningKeySelection {

  private SigningKeySelection() {
  }

  /**
   * Returns the entry whose key verified the file.
   *
   * @param keys the caller's trusted set; {@code null} is treated as empty.
   * @param signatureVerifies runs the file's signature check against one candidate's raw public
   *     key. Must not throw -- {@code alg} validation belongs before this call, not once per key.
   * @param claimedKeyId reads the file's own {@code kid} claim WITHOUT verifying anything, or
   *     returns {@code null} if it cannot be read. Called at most once, and only after every
   *     candidate has already failed.
   * @throws TamgaCheckoutException.NoUsableSigningKeyException if the set holds no usable key.
   * @throws TamgaCheckoutException.SigningKeyNotPublishedException if nothing verified and the
   *     file names the unpopulated-public-key sentinel.
   * @throws TamgaCheckoutException.UnknownSigningKeyException if nothing verified and the file
   *     names a key the set does not hold.
   * @throws TamgaCheckoutException.SignatureVerificationException if nothing verified and the file
   *     names a key the set DOES hold, or names none at all -- tampering, not rotation.
   */
  static SigningKeySet.Entry resolve(SigningKeySet keys, Predicate<byte[]> signatureVerifies,
      Supplier<String> claimedKeyId) {
    SigningKeySet set = keys == null ? SigningKeySet.empty() : keys;
    if (set.isEmpty()) {
      throw new TamgaCheckoutException.NoUsableSigningKeyException(set.skippedKeyIds());
    }

    for (SigningKeySet.Entry entry : set.entries()) {
      if (signatureVerifies.test(entry.publicKey())) {
        return entry;
      }
    }

    // Nothing verified. Only now is the payload worth looking at, and only for the one field that
    // separates the two failures.
    String claimed = claimedKeyId.get();
    if (claimed == null || set.contains(claimed)) {
      // Either the file will not say which key signed it, or it names a key that is right here and
      // the signature still fails. Both are tampering, not rotation.
      throw new TamgaCheckoutException.SignatureVerificationException();
    }
    if (Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID.equals(claimed)) {
      // Distinguishable from an ordinary stale set: no key set can ever hold this id, so
      // refetching is not the remedy. See the exception's own remarks.
      throw new TamgaCheckoutException.SigningKeyNotPublishedException(claimed, set.keyIds());
    }
    throw new TamgaCheckoutException.UnknownSigningKeyException(claimed, set.keyIds());
  }
}
