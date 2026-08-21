package sh.tamga.sdk.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns caller-chosen, labelled machine attributes into one canonical fingerprint string.
 *
 * <p><b>The defect this fixes was measured, not imagined.</b> Every SDK in this fleet sent the
 * caller's fingerprint string byte-for-byte, and the server stores {@code fingerprint TEXT NOT
 * NULL} with no length limit, no {@code CHECK} and no normalisation, unique per
 * {@code (license_id, fingerprint)}. So {@code "ABC-123"}, {@code "abc-123"} and
 * {@code " ABC-123 "} were three machines occupying three seats on one license. Trailing
 * whitespace off a config file or a shelled-out command is the usual way that happens.
 *
 * <pre>{@code
 * String fingerprint = Fingerprint.builder()
 *     .add("machine-id", readMachineId())
 *     .add("disk", diskSerial())
 *     .build();
 *
 * client.activateMachine(CreateMachineOptions.of(fingerprint, licenseId), null);
 * }</pre>
 *
 * <p><b>It reads no hardware identifiers, deliberately.</b> What identifies a machine is a product
 * decision: a cloned VM template shares its identifiers, a container has none, a replaced
 * motherboard changes them. No default is right for both a desktop application and a Kubernetes
 * sidecar, so the components are yours to choose and this class only fixes how they are combined.
 *
 * <h2>The rule</h2>
 *
 * <pre>
 * fingerprint = lowercase_hex(SHA-256(UTF-8(canonical)))
 * canonical   = "tamga-fingerprint-v1" US join(US, sort_bytewise(label + "=" + trimmed_value))
 * </pre>
 *
 * <p>where {@code US} is U+001F, the ASCII unit separator, emitted as the single byte {@code 0x1f}.
 * The literal prefix is a domain separator, so a future v2 rule cannot collide with v1 output.
 *
 * <ul>
 *   <li><b>Order does not matter.</b> Components are sorted, so the caller's ordering is their own
 *       convenience rather than part of the identity.
 *   <li><b>The sort is bytewise over UTF-8</b>, not {@link String#compareTo}, which compares UTF-16
 *       code units and disagrees above the BMP -- U+FB00 sorts before U+1F600 in UTF-8 bytes and
 *       after it in UTF-16 code units. Eight ports have to agree on one order.
 *   <li><b>Whitespace is trimmed from values</b> before validation, using the spec's ASCII set
 *       (space, tab, CR, LF, VT, FF) rather than {@link String#trim()}, which strips everything at
 *       or below U+0020 and would therefore silently swallow a leading NUL or BEL that must be
 *       <em>rejected</em>. {@code String.strip()} is wrong in the other direction: it removes
 *       Unicode whitespace such as U+00A0, which is a legal value character here.
 *   <li><b>Case is preserved.</b> Lowercasing a base64 or hex identifier corrupts it.
 *   <li><b>Values are NOT Unicode-normalised</b>, and that is a constraint rather than an
 *       oversight. The JDK has {@code java.text.Normalizer} and this class deliberately does not
 *       call it: NFC needs a new dependency in Rust and Go, and ICU or hand-rolled tables in C11.
 *       A rule eight ports cannot implement identically is worse than no rule -- it would produce
 *       two fingerprints for one machine depending on which SDK the application was written in,
 *       silently consuming two seats. A caller whose values can arrive in more than one normal
 *       form must normalise before calling.
 * </ul>
 *
 * <h2>Rejections</h2>
 *
 * <p>Invalid input throws {@link IllegalArgumentException} and is never quietly repaired.
 * Stripping a control character or de-duplicating a repeated label would map two different inputs
 * onto one fingerprint, and therefore onto one seat -- which is the very defect this class exists
 * to close.
 *
 * <ul>
 *   <li>A label must be non-empty and ASCII printable ({@code 0x21}-{@code 0x7E}) excluding
 *       {@code =}, so a label can never itself need normalising and the split at the first
 *       {@code =} is unambiguous.
 *   <li>A label may not repeat. Two values for one label is a caller bug, and picking one of them
 *       hides it.
 *   <li>A value, after trimming, may contain no ASCII control character ({@code 0x00}-{@code 0x1F},
 *       {@code 0x7F}). It may contain {@code =} and it may be empty -- a component that exists and
 *       reads empty is not the same as an absent component, because the label still contributes.
 *   <li>At least one component is required.
 * </ul>
 *
 * <p>This class is not thread-safe and is not meant to be: build one fingerprint per builder.
 */
public final class Fingerprint {

  /**
   * The domain separator every canonical string starts with.
   *
   * <p>Public so an application can record which rule produced a stored fingerprint. A future
   * {@code v2} rule would carry a different prefix, so output of the two can never collide even if
   * the rest of the algorithm were identical.
   */
  public static final String VERSION_PREFIX = "tamga-fingerprint-v1";

  /** U+001F, the ASCII unit separator, emitted as the single byte 0x1f. */
  static final char SEPARATOR = (char) 0x1f;

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private Fingerprint() {
  }

  /** Starts building a fingerprint. At least one component is required. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Collects the labelled components of one machine's fingerprint.
   *
   * <p>Each {@link #add} validates immediately, so a bad component is reported at the call that
   * introduced it rather than at {@link #build()}.
   */
  public static final class Builder {

    private final Set<String> labels = new LinkedHashSet<>();
    private final List<String> components = new ArrayList<>();

    private Builder() {
    }

    /**
     * Adds one labelled component.
     *
     * <p>The value is trimmed of ASCII whitespace at both ends <em>before</em> it is validated, so
     * {@code " abc "} and {@code "abc"} are the same component -- but a value carrying a control
     * character at either end is rejected rather than having it trimmed away.
     *
     * @param label a non-empty ASCII printable name, {@code 0x21}-{@code 0x7E} excluding {@code =},
     *     unique within this builder
     * @param value the component's value; may be empty, may contain {@code =}, may be non-ASCII
     * @return this builder
     * @throws IllegalArgumentException if either argument is null, the label is empty, repeated or
     *     outside the permitted character set, or the trimmed value contains an ASCII control
     *     character or a character with no UTF-8 encoding
     */
    public Builder add(String label, String value) {
      if (label == null) {
        throw new IllegalArgumentException("A fingerprint component label must not be null.");
      }
      if (value == null) {
        throw new IllegalArgumentException(
            "A fingerprint component value must not be null; use \"\" for an empty value.");
      }
      validateLabel(label);
      if (!labels.add(label)) {
        throw new IllegalArgumentException("Duplicate fingerprint component label: '" + label
            + "'. Two values for one label is a caller bug, and picking one of them would hide"
            + " it.");
      }
      String trimmed = trimAsciiWhitespace(value);
      validateValue(label, trimmed);
      components.add(label + '=' + trimmed);
      return this;
    }

    /**
     * Returns the fingerprint: 64 lowercase hex characters, the SHA-256 of the canonical string's
     * UTF-8 bytes.
     *
     * <p>The builder is left usable afterwards, so a caller may add a further component and build
     * again -- the two results are different fingerprints, as they should be.
     *
     * @throws IllegalArgumentException if no component was added
     */
    public String build() {
      byte[] digest = sha256(canonical().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(HEX_DIGITS[(b >> 4) & 0x0f]).append(HEX_DIGITS[b & 0x0f]);
      }
      return hex.toString();
    }

    /**
     * The exact string {@link #build()} hashes. Package-private: it is the algorithm's inside, not
     * a format to build on, but a test that asserts it pins the sort and the trim without needing
     * a digest to compare against.
     */
    String canonical() {
      if (components.isEmpty()) {
        throw new IllegalArgumentException(
            "A fingerprint needs at least one component; an empty one would identify every"
                + " machine equally.");
      }
      // Sorted on the UTF-8 bytes of the whole `label=value` component, not on the label alone and
      // not with String.compareTo. Sorting on the label alone would order "a-" before "a" where
      // the full-component rule orders them the other way, because '-' (0x2d) precedes '=' (0x3d).
      List<byte[]> encoded = new ArrayList<>(components.size());
      for (String component : components) {
        encoded.add(component.getBytes(StandardCharsets.UTF_8));
      }
      encoded.sort(Arrays::compareUnsigned);

      StringBuilder out = new StringBuilder();
      out.append(VERSION_PREFIX);
      for (byte[] component : encoded) {
        out.append(SEPARATOR).append(new String(component, StandardCharsets.UTF_8));
      }
      return out.toString();
    }
  }

  private static void validateLabel(String label) {
    if (label.isEmpty()) {
      throw new IllegalArgumentException("A fingerprint component label must not be empty.");
    }
    for (int i = 0; i < label.length(); i++) {
      char c = label.charAt(i);
      if (c == '=') {
        throw new IllegalArgumentException("A fingerprint component label may not contain '=',"
            + " which would make the split between label and value ambiguous, but '" + label
            + "' does.");
      }
      if (c < 0x21 || c > 0x7e) {
        throw new IllegalArgumentException("A fingerprint component label must be ASCII printable"
            + " (0x21-0x7E), so that it can never itself need Unicode normalising, but '" + label
            + "' contains " + codePointName(c) + ".");
      }
    }
  }

  private static void validateValue(String label, String trimmed) {
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c <= 0x1f || c == 0x7f) {
        throw new IllegalArgumentException("The value for fingerprint component '" + label
            + "' contains the control character " + codePointName(c)
            + ". Control characters are rejected rather than stripped: stripping would map two"
            + " different machines onto one fingerprint, and so onto one seat.");
      }
      // A lone surrogate has no UTF-8 encoding at all, and Java's encoder would silently replace
      // it with the replacement character -- turning two different values into one fingerprint,
      // which is precisely what every other rule here exists to prevent. Java is the only port of
      // this rule whose string type can hold one, so this rejects rather than repairs.
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= trimmed.length() || !Character.isLowSurrogate(trimmed.charAt(i + 1))) {
          throw new IllegalArgumentException(unpairedSurrogateMessage(label, c));
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException(unpairedSurrogateMessage(label, c));
      }
    }
  }

  private static String unpairedSurrogateMessage(String label, char c) {
    return "The value for fingerprint component '" + label + "' contains the unpaired surrogate "
        + codePointName(c) + ", which has no UTF-8 encoding. Encoding it would substitute the"
        + " replacement character and silently merge two different values into one fingerprint.";
  }

  private static String codePointName(char c) {
    return String.format("U+%04X", (int) c);
  }

  /**
   * Trims the spec's ASCII whitespace set -- space, tab, LF, VT, FF, CR -- from both ends.
   *
   * <p>Neither {@link String#trim()} nor {@code String.strip()} implements this set.
   * {@code trim()} removes every character at or below U+0020, so it would swallow a leading NUL
   * or BEL that must be rejected instead; {@code strip()} removes Unicode whitespace such as
   * U+00A0 and U+2028, which are legal value characters here and must survive into the hash.
   */
  static String trimAsciiWhitespace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isAsciiWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && isAsciiWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  private static boolean isAsciiWhitespace(char c) {
    // 0x0b is the vertical tab, which Java has no short escape for.
    return c == ' ' || c == '\t' || c == '\n' || c == '\f'
        || c == '\r' || c == 0x0b;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      // Every Java SE implementation is required to provide SHA-256, so this cannot happen on a
      // conforming JVM -- but it is a checked exception and swallowing it would be worse.
      throw new IllegalStateException("SHA-256 is unavailable in this JVM.", e);
    }
  }
}
