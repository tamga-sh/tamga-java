package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  @Test
  void objectKeysAreSortedAlphabeticallyNotSourceOrder() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("zebra", "z");
    value.put("apple", "a");
    value.put("mango", "m");

    String expected = "{\"apple\":\"a\",\"mango\":\"m\",\"zebra\":\"z\"}";
    assertThat(CanonicalJson.serialize(value)).isEqualTo(expected);
  }

  @Test
  void sortingIsRecursiveAtEveryNestingLevel() {
    // Matches the exact worked example from OfflineProof's remarks: dataset sorts before
    // machine, and fingerprint sorts before id within machine.
    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put("id", "m1");
    machine.put("fingerprint", "fp1");

    Map<String, Object> account = new LinkedHashMap<>();
    account.put("id", "a1");

    Map<String, Object> dataset = new LinkedHashMap<>();
    dataset.put("z", 1);
    dataset.put("a", 2);

    Map<String, Object> value = new LinkedHashMap<>();
    value.put("machine", machine);
    value.put("account", account);
    value.put("dataset", dataset);

    String expected = "{\"account\":{\"id\":\"a1\"},\"dataset\":{\"a\":2,\"z\":1},"
        + "\"machine\":{\"fingerprint\":\"fp1\",\"id\":\"m1\"}}";
    assertThat(CanonicalJson.serialize(value)).isEqualTo(expected);
  }

  @Test
  void arraysKeepTheirOriginalElementOrder() {
    assertThat(CanonicalJson.serialize(Arrays.asList(3, 1, 2))).isEqualTo("[3,1,2]");
  }

  @Test
  void integersSerializeWithoutDecimalPointDistinctFromDoubles() {
    // Regression: collapsing Integer/Double into one representation would reformat a plain
    // integer field (e.g. 5) as "5.0" -- a byte that wouldn't match what the server signed.
    assertThat(CanonicalJson.serialize(5)).isEqualTo("5");
    assertThat(CanonicalJson.serialize(5.5)).isEqualTo("5.5");
  }

  @Test
  void longIntegersSerializeWithoutDecimalPoint() {
    assertThat(CanonicalJson.serialize(9_999_999_999L)).isEqualTo("9999999999");
  }

  /**
   * SECURITY regression -- empirically confirmed (see CanonicalJson's Javadoc): {@code
   * String.compareTo()} genuinely diverges from UTF-8 byte order for this exact adversarial pair
   * (an astral-plane character sorts before a BMP private-use character under UTF-16 code-unit
   * order, but after it under UTF-8 byte order). This is the same bug class that broke tamga-js's
   * canonicalJson.ts.
   */
  @Test
  void keysAreSortedByUtf8ByteOrderNotStringCompareTo() {
    // Built from codepoints, not literal source characters or numeric escapes, to sidestep this
    // repo's Checkstyle rule as well as this toolchain's own non-ASCII text normalization (see
    // stringEscapingMatchesSerdeJsonDefault's remarks below for more on that). U+10000 needs a
    // surrogate pair; U+E000 is in the unassigned Private Use Area.
    String astral = new String(Character.toChars(0x10000)); // 4-byte UTF-8, UTF-16 surrogate pair
    String bmpPrivateUse = String.valueOf((char) 0xE000); // 3-byte UTF-8, single UTF-16 code unit

    // Confirm the two orderings genuinely disagree for this pair -- otherwise this test would
    // pass even with the bug (String.compareTo used instead of UTF-8 byte order).
    assertThat(astral.compareTo(bmpPrivateUse)).isNegative();

    Map<String, Object> value = new LinkedHashMap<>();
    value.put(astral, "astral");
    value.put(bmpPrivateUse, "bmp-private-use");
    value.put("a", "ascii");

    String serialized = CanonicalJson.serialize(value);
    int asciiPos = serialized.indexOf("\"ascii\"");
    int bmpPos = serialized.indexOf("\"bmp-private-use\"");
    int astralPos = serialized.indexOf("\"astral\"");

    assertThat(asciiPos).isLessThan(bmpPos);
    assertThat(bmpPos).isLessThan(astralPos);
  }

  @Test
  void stringEscapingMatchesSerdeJsonDefault() {
    assertThat(CanonicalJson.serialize("simple")).isEqualTo("\"simple\"");
    assertThat(CanonicalJson.serialize("has \"quotes\"")).isEqualTo("\"has \\\"quotes\\\"\"");
    assertThat(CanonicalJson.serialize("back\\slash")).isEqualTo("\"back\\\\slash\"");
    assertThat(CanonicalJson.serialize("line\nbreak")).isEqualTo("\"line\\nbreak\"");
    assertThat(CanonicalJson.serialize("tab\ttab")).isEqualTo("\"tab\\ttab\"");
    // Non-ASCII is emitted raw, NOT given a numeric escape. Built from char values rather than
    // literal source characters or numeric escapes -- both keep getting silently normalized by
    // this toolchain's own text handling, so char-value construction is the only reliably
    // reproducible way to express this test.
    // Latin small letter e with acute (U+00E9), appended to "caf".
    String accented = "caf" + (char) 0xe9;
    // Three CJK characters: U+65E5, U+672C, U+8A9E.
    String japanese = String.valueOf(new char[] {(char) 0x65e5, (char) 0x672c, (char) 0x8a9e});
    String withNonAscii = accented + " " + japanese;
    String expectedNonAscii = "\"" + withNonAscii + "\"";
    assertThat(CanonicalJson.serialize(withNonAscii)).isEqualTo(expectedNonAscii);
    // Forward slash is NOT escaped (serde_json doesn't escape it either).
    assertThat(CanonicalJson.serialize("a/b")).isEqualTo("\"a/b\"");
  }

  @Test
  void controlCharactersOutsideTheShortEscapeSetBecomeUnicodeEscapes() {
    // Both inputs and expected outputs are built from char values / concatenated fragments,
    // never a single literal containing a backslash immediately followed by hex digits -- this
    // repo's Checkstyle config flags that shape even when (as here) it's produced by deliberate
    // double-escaping (a literal backslash + the literal text "uXXXX") rather than an actual
    // numeric escape sequence.
    String controlOne = String.valueOf((char) 0x01);
    String controlUnitSeparator = String.valueOf((char) 0x1F);
    String nulByteInTheMiddle = "a" + (char) 0x00 + "b";
    String backslash = String.valueOf('\\');

    String expectedOne = "\"" + backslash + "u0001\"";
    String expectedUnitSeparator = "\"" + backslash + "u001f\"";
    String expectedNulInTheMiddle = "\"a" + backslash + "u0000b\"";

    assertThat(CanonicalJson.serialize(controlOne)).isEqualTo(expectedOne);
    assertThat(CanonicalJson.serialize(controlUnitSeparator)).isEqualTo(expectedUnitSeparator);
    assertThat(CanonicalJson.serialize(nulByteInTheMiddle)).isEqualTo(expectedNulInTheMiddle);
  }

  @Test
  void backspaceFormFeedAndCarriageReturnUseTheirShortEscapes() {
    // Regression: only \n and \t were exercised elsewhere -- \b/\f/\r are separate branches in
    // writeEscapedString's switch and were previously untested.
    assertThat(CanonicalJson.serialize("a" + (char) 0x08 + "b")).isEqualTo("\"a\\bb\"");
    assertThat(CanonicalJson.serialize("a" + (char) 0x0c + "b")).isEqualTo("\"a\\fb\"");
    assertThat(CanonicalJson.serialize("a" + (char) 0x0d + "b")).isEqualTo("\"a\\rb\"");
  }

  @Test
  void serializeThrowsForAnUnsupportedValueType() {
    assertThatThrownBy(() -> CanonicalJson.serialize(new Object()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported JSON value type");
  }

  @Test
  void nullAndBooleanSerializeAsTheirJsonLiterals() {
    Object nullValue = null;
    assertThat(CanonicalJson.serialize(nullValue)).isEqualTo("null");
    assertThat(CanonicalJson.serialize(true)).isEqualTo("true");
    assertThat(CanonicalJson.serialize(false)).isEqualTo("false");
  }

  @Test
  void outputHasNoWhitespace() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("a", 1);
    value.put("b", Arrays.asList(1, 2));

    String serialized = CanonicalJson.serialize(value);

    assertThat(serialized).doesNotContain(" ").doesNotContain("\n");
  }
}
