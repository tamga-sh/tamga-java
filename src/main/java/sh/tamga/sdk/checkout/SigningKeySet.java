package sh.tamga.sdk.checkout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.SigningKey;

/**
 * The Ed25519 keys an offline file is allowed to have been signed by, indexed by the {@code kid}
 * its claims name.
 *
 * <p><b>The problem this closes.</b> Verifying against one embedded public key collapses two
 * completely different outcomes into one error. A file signed last month, before the account
 * rotated its signing key, is authentic and its license may well still be valid -- but against the
 * current key it fails with exactly the error a forgery produces, and the caller cannot tell "my
 * key set is stale" from "this file was tampered with". The first calls for fetching the key set
 * or shipping an update; the second calls for refusing the customer. Through a key set the two are
 * {@link TamgaCheckoutException.UnknownSigningKeyException} and {@link
 * TamgaCheckoutException.SignatureVerificationException} respectively.
 *
 * <p><b>How the {@code kid} is used, and why that is safe.</b> The claim lives INSIDE the signed
 * (and possibly encrypted) payload, so reading it to pick a key would mean parsing
 * attacker-supplied bytes before anything has vouched for them -- inverting the one ordering rule
 * this package is built on. So the verifiers here do the opposite: every key in the set is tried
 * against the signature first, and the happy path never touches the payload unverified. The claim
 * is read only after every key has failed, at which point the file is already known not to be
 * authentic under anything held, and the only remaining question is which error to report. Its
 * value picks an error label and is used for nothing else; it can never introduce a key.
 *
 * <p>That ordering has a second payoff. A file whose {@code kid} is {@link
 * Ed25519#UNPUBLISHED_ACCOUNT_KEY_ID} -- issued by an account whose public-key column was never
 * populated -- names no resolvable key at all, yet is genuinely signed. Selecting by {@code kid}
 * would fail on it even with the right key in hand; trying the keys first verifies it normally.
 *
 * <p>There is deliberately no "try every key, then accept the file anyway" fallback in the failure
 * path either: trying them all is what happens, and when none verifies the file is refused. What
 * the {@code kid} adds is only the reason.
 *
 * <p><b>Ed25519 only.</b> Every key the server publishes is Ed25519 ({@code rotate_ed25519} is the
 * only writer), and {@code .lic} files are Ed25519-signed regardless of the license's own {@code
 * scheme}. A {@code .machine} file signed under an RSA or ECDSA scheme cannot be verified through
 * this path at all -- its key is not published, is never rotated, and its {@code kid} claim names
 * the account's Ed25519 key anyway. Verify those with {@link MachineFile#verifyWithClaims(
 * sh.tamga.sdk.model.LicenseScheme, byte[], String, String, long)} and the license's own scheme,
 * and accept that a rotation is not a distinguishable outcome for them.
 *
 * <p>Immutable and safe to share across threads -- build one per process and keep it.
 */
public final class SigningKeySet {

  /** Raw Ed25519 public keys are exactly this many bytes. */
  private static final int ED25519_PUBLIC_KEY_LENGTH = 32;

  private final List<Entry> entries;
  private final List<String> keyIds;
  private final List<String> mismatchedKeyIds;
  private final List<String> skippedKeyIds;

  private SigningKeySet(List<Entry> entries, List<String> skippedKeyIds) {
    List<String> ids = new ArrayList<>(entries.size());
    List<String> mismatched = new ArrayList<>();
    for (Entry entry : entries) {
      ids.add(entry.key.keyId());
      if (!entry.computedKeyId.equals(entry.key.keyId())) {
        mismatched.add(entry.key.keyId());
      }
    }
    this.entries = Collections.unmodifiableList(entries);
    this.keyIds = Collections.unmodifiableList(ids);
    this.mismatchedKeyIds = Collections.unmodifiableList(mismatched);
    this.skippedKeyIds = Collections.unmodifiableList(skippedKeyIds);
  }

  /** A key set holding nothing. Every verification through it reports no usable key. */
  public static SigningKeySet empty() {
    return new SigningKeySet(Collections.<Entry>emptyList(), Collections.<String>emptyList());
  }

  /**
   * Builds a key set from the account's published keys, as returned by {@code
   * TamgaClient.listSigningKeys()}.
   *
   * <p>Lenient where {@link #ofPublicKeys} is strict, and for the opposite reason: this input is
   * the server's whole key history, and one unusable row -- a future non-Ed25519 algorithm, a
   * legacy key that does not decode -- must not strand every file the account has already signed.
   * Such entries are skipped, and a file naming one surfaces as {@link
   * TamgaCheckoutException.UnknownSigningKeyException} with the id in hand. Compare {@link #size()}
   * against the number of resources fetched if you need to know that something was dropped.
   *
   * <p>Each key's id is taken from the resource {@code id}, which IS the {@code kid} -- the server
   * sets it from the same value it writes into the file's claim, so no local hashing is required
   * on this path. It is computed anyway as a cross-check: see {@link #mismatchedKeyIds()}.
   */
  public static SigningKeySet of(Collection<SigningKey> keys) {
    List<Entry> entries = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    if (keys != null) {
      for (SigningKey key : keys) {
        Entry entry = entryFor(key);
        if (entry == null) {
          skipped.add(key == null ? "(null)" : String.valueOf(key.keyId()));
        } else {
          entries.add(entry);
        }
      }
    }
    return new SigningKeySet(entries, skipped);
  }

  /**
   * Builds a key set from public keys the caller holds, each standard base64 of the raw 32 bytes
   * -- the format the server publishes and stores. Each key's id is derived locally, so this needs
   * no network access at all.
   *
   * <p><b>This is the path an embedded client actually has.</b> The signing-keys route requires
   * the {@code account.read} permission, which a license-key credential does not hold, so pinning
   * the account's published key (or keys, across a rotation) in the application binary is how an
   * offline verifier stays offline.
   *
   * <p>Strict on purpose: a key that is not valid base64 of exactly 32 bytes throws rather than
   * being skipped. A typo in a key pinned in a binary must fail loudly at startup, not quietly
   * produce a set that reports every genuine file as signed by an unknown key, at runtime, in the
   * field.
   *
   * @throws TamgaCheckoutException.OfflineFileFormatException if any key is null, is not valid
   *     base64, or does not decode to exactly 32 bytes.
   */
  public static SigningKeySet ofPublicKeys(Collection<String> publicKeysBase64) {
    List<Entry> entries = new ArrayList<>();
    if (publicKeysBase64 != null) {
      for (String publicKey : publicKeysBase64) {
        byte[] decoded = decodeOrThrow(publicKey);
        String keyId = Ed25519.keyId(publicKey);
        entries.add(new Entry(SigningKey.ed25519(keyId, publicKey), decoded, keyId));
      }
    }
    return new SigningKeySet(entries, Collections.<String>emptyList());
  }

  /** As {@link #ofPublicKeys(Collection)}, for keys listed inline. */
  public static SigningKeySet ofPublicKeys(String... publicKeysBase64) {
    return ofPublicKeys(publicKeysBase64 == null ? Collections.<String>emptyList()
        : Arrays.asList(publicKeysBase64));
  }

  /** How many usable keys the set holds. */
  public int size() {
    return entries.size();
  }

  /**
   * Whether the set holds no usable key at all.
   *
   * <p>Not an error in itself -- every verification through it reports {@link
   * TamgaCheckoutException.NoUsableSigningKeyException}, which is the honest answer -- but it is
   * almost always a sign that the fetch or the embedded key list is wrong.
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * The key ids this set can verify against, in the order it holds them. Useful in a log line next
   * to an unknown-key failure.
   */
  public List<String> keyIds() {
    return keyIds;
  }

  /**
   * The ids of keys whose published id does not match the one computed locally from their public
   * key.
   *
   * <p>Always empty against a correct server, and worth reporting upstream if it ever is not: it
   * means the account published a key under an id that is not the {@link Ed25519#keyId(String)} of
   * its own public key, which no client can fix.
   *
   * <p><b>This is a reportable condition, not a second lookup path.</b> {@link #contains} matches
   * the served id alone -- the id the file's own {@code kid} is drawn from -- so a mislabelled key
   * is surfaced here rather than silently absorbed by a fallback that would hide it. Note what
   * this does and does not cost: keys are tried against the signature before any id is consulted
   * (see {@link SigningKeySelection}), so a mislabelled key still verifies its own files normally.
   * The served-id rule only decides which error a file that verified under NO key reports.
   */
  public List<String> mismatchedKeyIds() {
    return mismatchedKeyIds;
  }

  /**
   * The ids of published keys this set dropped as unusable -- another algorithm, an undecodable
   * public key, or a resource missing its id.
   *
   * <p>This is what {@link #of} skipped rather than failed on, and it is the answer to "the fetch
   * returned five keys and the set holds three". Empty for a set built with {@link #ofPublicKeys},
   * which throws instead of skipping.
   */
  public List<String> skippedKeyIds() {
    return skippedKeyIds;
  }

  /**
   * Whether the set holds a key under this id, matched against the published id or the locally
   * computed one.
   *
   * <p>Matching is exact and case-sensitive: the server emits lowercase hex on both sides, in the
   * resource id and in the file's claim alike.
   */
  public boolean contains(String keyId) {
    return find(keyId) != null;
  }

  /**
   * The entry held under {@code keyId}, or {@code null}. Package-private -- the public form of
   * this question is {@link #contains(String)}, which cannot hand out key bytes.
   */
  Entry find(String keyId) {
    if (keyId == null) {
      return null;
    }
    for (Entry entry : entries) {
      // The SERVED id only. The file's own kid and this id are both key_id() of the same stored
      // public key, so a disagreement means the server's metadata is wrong -- and matching the
      // locally computed id as a second spelling would invent a rule the wire does not have and
      // quietly absorb the very signal that says so. The disagreement is reported instead, by
      // mismatchedKeyIds(). Fleet-wide rule, matching tamga-rust and tamga-dotnet.
      if (keyId.equals(entry.key.keyId())) {
        return entry;
      }
    }
    return null;
  }

  /** The usable entries, in the order the set holds them. */
  List<Entry> entries() {
    return entries;
  }

  private static Entry entryFor(SigningKey key) {
    if (key == null || key.keyId() == null || key.publicKey() == null) {
      return null;
    }
    // An entry for another algorithm cannot verify an Ed25519 signature; skipping it rather than
    // trying it is what lets the failure path say "no usable key" instead of "unknown key".
    if (!SigningKey.ED25519_ALGORITHM.equalsIgnoreCase(key.algorithm())) {
      return null;
    }
    byte[] decoded = Base64Codec.decodeOrNull(key.publicKey());
    if (decoded == null || decoded.length != ED25519_PUBLIC_KEY_LENGTH) {
      return null;
    }
    return new Entry(key, decoded, Ed25519.keyId(key.publicKey()));
  }

  private static byte[] decodeOrThrow(String publicKey) {
    byte[] decoded = publicKey == null ? null : Base64Codec.decodeOrNull(publicKey);
    if (decoded == null || decoded.length != ED25519_PUBLIC_KEY_LENGTH) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "A pinned signing key must be standard base64 of a raw " + ED25519_PUBLIC_KEY_LENGTH
              + "-byte Ed25519 public key; got "
              + (publicKey == null ? "null" : "'" + publicKey + "'") + ".");
    }
    return decoded;
  }

  /** One usable key: the published record, its decoded bytes, and its locally computed id. */
  static final class Entry {
    private final SigningKey key;
    private final byte[] publicKey;
    private final String computedKeyId;

    Entry(SigningKey key, byte[] publicKey, String computedKeyId) {
      this.key = key;
      this.publicKey = publicKey.clone();
      this.computedKeyId = computedKeyId;
    }

    SigningKey key() {
      return key;
    }

    /** The raw 32 bytes, copied so the set stays immutable however a verifier treats them. */
    byte[] publicKey() {
      return publicKey.clone();
    }
  }
}
