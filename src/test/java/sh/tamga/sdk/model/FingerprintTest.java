package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh.tamga.sdk.support.FingerprintVectors;

/**
 * Pins {@link Fingerprint} against vectors this SDK did not generate -- see
 * {@link FingerprintVectors} for why that distinction is the whole point, and for why the file is
 * decoded as UTF-8 explicitly.
 *
 * <p>The vectors cover the rule the eight ports must agree on. The tests below them cover what a
 * Java port specifically can get wrong while still passing every vector: {@code trim()} strips a
 * wider set of characters than the spec, {@code strip()} strips a different set again, and the
 * sort key has two plausible spellings the vector file does not separate.
 */
class FingerprintTest {

  /** U+001F, the unit separator the canonical string joins on. */
  private static final String US = String.valueOf((char) 0x1f);

  private static final char BEL = (char) 0x07;
  private static final char NUL = (char) 0x00;
  private static final char VERTICAL_TAB = (char) 0x0b;
  private static final char FORM_FEED = (char) 0x0c;
  private static final char NBSP = (char) 0x00a0;
  private static final char IDEOGRAPHIC_SPACE = (char) 0x3000;
  private static final char LINE_SEPARATOR = (char) 0x2028;
  private static final char UNIT_SEPARATOR = (char) 0x1f;
  private static final char DELETE = (char) 0x7f;
  private static final char CARRIAGE_RETURN = (char) 0x0d;
  private static final char ZERO_WIDTH_SPACE = (char) 0x200b;
  private static final char E_ACUTE = (char) 0x00e9;
  private static final char COMBINING_ACUTE = (char) 0x0301;
  private static final char HIGH_SURROGATE = (char) 0xd83d;
  private static final char LOW_SURROGATE = (char) 0xde00;

  @BeforeAll
  static void vectorFileDecodedAsUtf8() {
    // Stated first, so a platform-default reader on Windows before Java 18 reports itself instead
    // of surfacing as an unexplained digest mismatch on one CI leg.
    FingerprintVectors.assertDecodedAsUtf8();
  }

  private static List<FingerprintVectors.Vector> vectors() {
    return FingerprintVectors.vectors();
  }

  private static List<FingerprintVectors.Rejected> rejected() {
    return FingerprintVectors.rejected();
  }

  private static Fingerprint.Builder builderOf(List<String[]> components) {
    Fingerprint.Builder builder = Fingerprint.builder();
    for (String[] component : components) {
      builder.add(component[0], component[1]);
    }
    return builder;
  }

  private static String fingerprintOf(String name) {
    for (FingerprintVectors.Vector vector : vectors()) {
      if (name.equals(vector.name())) {
        return builderOf(vector.components()).build();
      }
    }
    throw new IllegalStateException("No vector named " + name);
  }

  // ----------------------------------------------------------------- vectors

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void everyVectorReproducesItsFingerprint(FingerprintVectors.Vector vector) {
    assertThat(builderOf(vector.components()).build()).isEqualTo(vector.fingerprint());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void everyVectorReproducesItsCanonicalString(FingerprintVectors.Vector vector) {
    // The digest alone cannot say WHERE an implementation diverged. The canonical string can, and
    // it is also what a support conversation between two SDKs compares.
    assertThat(builderOf(vector.components()).canonical()).isEqualTo(vector.canonical());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejected")
  void everyRejectedCaseThrowsRatherThanBeingRepaired(FingerprintVectors.Rejected rejected) {
    // Quietly repairing any of these -- stripping the control character, de-duplicating the label
    // -- maps two different inputs onto one fingerprint, and so onto one seat. That is the defect
    // this class exists to close, so it must never be the way it fails.
    assertThatThrownBy(() -> builderOf(rejected.components()).build())
        .as(rejected.name() + ": " + rejected.reason())
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -------------------------------------------------------------- invariants

  @Test
  void componentOrderIsTheCallersConvenienceNotPartOfTheIdentity() {
    assertThat(fingerprintOf("two_unsorted")).isEqualTo(fingerprintOf("two_sorted"));
  }

  @Test
  void surroundingWhitespaceIsAbsorbedRatherThanMakingOneMoreMachine() {
    // The footgun this helper exists for: a value read from a config file or a shelled-out command
    // arrives with a trailing newline, and the server counts it as a second seat.
    assertThat(fingerprintOf("whitespace_trimmed")).isEqualTo(fingerprintOf("single"));
  }

  @Test
  void caseIsPreservedBecauseLoweringAnIdentifierCorruptsIt() {
    assertThat(fingerprintOf("case_preserved")).isNotEqualTo(fingerprintOf("single"));
  }

  @Test
  void theFingerprintIs64LowercaseHexCharacters() {
    for (FingerprintVectors.Vector vector : vectors()) {
      assertThat(vector.fingerprint()).hasSize(64).matches("[0-9a-f]{64}");
      assertThat(builderOf(vector.components()).build()).hasSize(64);
    }
  }

  @Test
  void theCanonicalStringCarriesTheDomainPrefixSoFutureRulesCannotCollide() {
    String canonical = Fingerprint.builder().add("machine-id", "abc123").canonical();

    assertThat(canonical).startsWith(Fingerprint.VERSION_PREFIX + US);
    assertThat(Fingerprint.VERSION_PREFIX).isEqualTo("tamga-fingerprint-v1");
  }

  // ------------------------------------------------------------ the sort key

  @Test
  void componentsSortOnTheWholeLabelEqualsValueNotOnTheLabelAlone() {
    // The two spellings disagree here and nowhere in the vector file: '-' (0x2d) precedes
    // '=' (0x3d), so "a-b=y" sorts BEFORE "a=x" on the whole component, while sorting on the label
    // alone puts "a" before "a-b" and reverses them. Asserted on the canonical string, so it needs
    // no digest of its own.
    String canonical = Fingerprint.builder().add("a", "x").add("a-b", "y").canonical();

    assertThat(canonical).isEqualTo(Fingerprint.VERSION_PREFIX + US + "a-b=y" + US + "a=x");
  }

  @Test
  void theSortIsCaseSensitiveSoAnUppercaseLabelLeadsLowercaseOnes() {
    // 'B' is 0x42 and 'a' is 0x61, so a bytewise sort puts B first. A case-insensitive comparison
    // -- what String.CASE_INSENSITIVE_ORDER or a locale-aware collator would apply -- reverses
    // them. Every label in the vector file is lowercase, so only this separates the two.
    String canonical = Fingerprint.builder().add("a", "1").add("B", "2").canonical();

    assertThat(canonical).isEqualTo(Fingerprint.VERSION_PREFIX + US + "B=2" + US + "a=1");
  }

  @Test
  void theSortIgnoresValuesBecauseTheLabelAlwaysDecidesIt() {
    // Worth stating in a test because it is the reason NO test here distinguishes a bytewise UTF-8
    // sort from String.compareTo, even though those two genuinely disagree above the BMP. Labels
    // are ASCII printable and unique, so two components always differ at an ASCII position inside
    // `label=`, before either value is reached -- and at an ASCII position the byte order, the
    // UTF-16 code-unit order and the code-point order are the same order. The implementation sorts
    // on UTF-8 bytes because that is what the shared rule says and what the other ports do, not
    // because this suite could catch the alternative.
    String first = Fingerprint.builder().add("a", "" + ZERO_WIDTH_SPACE).add("b", "z").canonical();
    String second = Fingerprint.builder().add("a", "z").add("b", "" + ZERO_WIDTH_SPACE).canonical();

    assertThat(first)
        .isEqualTo(Fingerprint.VERSION_PREFIX + US + "a=" + ZERO_WIDTH_SPACE + US + "b=z");
    assertThat(second)
        .isEqualTo(Fingerprint.VERSION_PREFIX + US + "a=z" + US + "b=" + ZERO_WIDTH_SPACE);
  }

  // ---------------------------------------------------------------- trimming

  @Test
  void controlCharactersAtTheEdgesAreRejectedRatherThanTrimmedAway() {
    // String.trim() strips every character at or below U+0020, so it would swallow this BEL and
    // accept the value -- silently merging it with the value that has no BEL. The spec's
    // whitespace set is narrower than trim()'s on purpose, and the vector file's control_in_value
    // case puts its control character in the MIDDLE, where trim() would never have reached it.
    assertThatThrownBy(() -> Fingerprint.builder().add("id", "abc" + BEL).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+0007");

    assertThatThrownBy(() -> Fingerprint.builder().add("id", NUL + "abc").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+0000");

    // DEL sits ABOVE the 0x00-0x1F block, so a check written as "c < 0x20" misses it and a check
    // written as "c <= 0x1F" needs a second clause. The vector file has no DEL case.
    assertThatThrownBy(() -> Fingerprint.builder().add("id", "ab" + DELETE + "c").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+007F");
  }

  @Test
  void verticalTabAndFormFeedAreTrimmedBecauseValidationRunsAfterTheTrim() {
    // The other half of the same ordering: VT and FF are control characters AND members of the
    // spec's whitespace set, so validating before trimming would reject a value the rule accepts.
    // The whitespace_trimmed vector uses only space, tab and newline, so it cannot catch this.
    String padded =
        Fingerprint.builder().add("machine-id", VERTICAL_TAB + "abc123" + FORM_FEED).build();

    assertThat(padded).isEqualTo(fingerprintOf("single"));
  }

  @Test
  void carriageReturnIsTrimmedSoWindowsLineEndingsMakeNoExtraMachine() {
    // The whitespace_trimmed vector ends its value with a bare LF. A value read from a file
    // written on Windows ends with CR LF, and the CR is the half that vector never exercises.
    String padded = Fingerprint.builder()
        .add("machine-id", "abc123" + CARRIAGE_RETURN + (char) 0x0a)
        .build();

    assertThat(padded).isEqualTo(fingerprintOf("single"));
  }

  @Test
  void unicodeWhitespaceSurvivesBecauseOnlyTheAsciiSetIsTrimmed() {
    // String.strip() is wrong in the other direction from trim(): it removes every character
    // Character.isWhitespace accepts, which deletes these two and merges values that differ.
    // U+00A0 is deliberately NOT one of them -- isWhitespace() rejects a non-breaking space, so a
    // test built only on U+00A0 would pass under strip() and prove nothing. Measured, after the
    // first version of this test survived exactly that mutation.
    String ideographic = Fingerprint.builder().add("id", IDEOGRAPHIC_SPACE + "abc").canonical();
    String lineSeparator = Fingerprint.builder().add("id", "abc" + LINE_SEPARATOR).canonical();

    assertThat(ideographic)
        .isEqualTo(Fingerprint.VERSION_PREFIX + US + "id=" + IDEOGRAPHIC_SPACE + "abc");
    assertThat(lineSeparator)
        .isEqualTo(Fingerprint.VERSION_PREFIX + US + "id=abc" + LINE_SEPARATOR);
  }

  @Test
  void nonBreakingSpaceSurvivesBecauseItIsNotAsciiWhitespace() {
    // U+00A0 catches no trimming mutation on its own -- neither trim() nor strip() removes it --
    // but it is the character a caller is most likely to have in a value copied out of a document,
    // so what happens to it is worth stating.
    String canonical = Fingerprint.builder().add("id", NBSP + "abc").canonical();

    assertThat(canonical).isEqualTo(Fingerprint.VERSION_PREFIX + US + "id=" + NBSP + "abc");
    assertThat(Fingerprint.builder().add("id", NBSP + "abc").build())
        .isNotEqualTo(Fingerprint.builder().add("id", "abc").build());
  }

  @Test
  void theSeparatorAtTheEdgeOfValuesIsRejectedRatherThanTrimmedAway() {
    // U+001F is both the canonical string's separator and a character Character.isWhitespace
    // accepts, so String.strip() would quietly delete it here. The vector file's
    // separator_in_value case puts it in the middle, where no trimming reaches it.
    assertThatThrownBy(() -> Fingerprint.builder().add("id", UNIT_SEPARATOR + "abc").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+001F");
  }

  @Test
  void valuesOfNothingButWhitespaceTrimToTheEmptyValue() {
    assertThat(Fingerprint.builder().add("machine-id", "   ").build())
        .isEqualTo(fingerprintOf("empty_value"));
  }

  // ---------------------------------------------------- rejections and nulls

  @Test
  void unicodeNormalisationIsDeliberatelyAbsentSoEightPortsCanAgree() {
    // The composed and decomposed spellings of the same text are different fingerprints here, and
    // that is the documented constraint rather than a defect: NFC needs a new dependency in Rust
    // and Go and ICU in C11, and a rule eight ports cannot implement identically would give one
    // machine two fingerprints depending on which SDK the application used.
    String composed = Fingerprint.builder().add("owner", "caf" + E_ACUTE).build();
    String decomposed = Fingerprint.builder().add("owner", "cafe" + COMBINING_ACUTE).build();

    assertThat(composed).isNotEqualTo(decomposed);
    // And the composed spelling is the one the shared vector carries.
    assertThat(composed).isEqualTo(fingerprintOf("non_ascii_value"));
  }

  @Test
  void unpairedSurrogatesAreRejectedBecauseTheyHaveNoUtf8Encoding() {
    // Java is the only port of this rule whose string type can hold one. Encoding it substitutes
    // the replacement character, so two different values would hash to the same fingerprint --
    // exactly the merge every other rule here prevents.
    assertThatThrownBy(() -> Fingerprint.builder().add("id", "a" + HIGH_SURROGATE + "b").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+D83D");

    assertThatThrownBy(() -> Fingerprint.builder().add("id", "" + LOW_SURROGATE).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+DE00");
  }

  @Test
  void wellFormedSurrogatePairsAreAcceptedAndKeptWhole() {
    String canonical = Fingerprint.builder()
        .add("emoji", "" + HIGH_SURROGATE + LOW_SURROGATE)
        .canonical();

    assertThat(canonical).isEqualTo(
        Fingerprint.VERSION_PREFIX + US + "emoji=" + HIGH_SURROGATE + LOW_SURROGATE);
  }

  @Test
  void nullArgumentsAreRefusedWithMessagesRatherThanNullPointers() {
    assertThatThrownBy(() -> Fingerprint.builder().add(null, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");

    assertThatThrownBy(() -> Fingerprint.builder().add("id", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value");
  }

  @Test
  void repeatedLabelsAreRefusedAtTheCallThatIntroducedThem() {
    Fingerprint.Builder builder = Fingerprint.builder().add("id", "a");

    assertThatThrownBy(() -> builder.add("id", "b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void buildersWithNoComponentsRefuseToProduceFingerprints() {
    assertThatThrownBy(() -> Fingerprint.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one component");
  }

  @Test
  void addingOneMoreComponentProducesDifferentOutput() {
    Fingerprint.Builder builder = Fingerprint.builder().add("machine-id", "abc123");
    String before = builder.build();

    String after = builder.add("disk", "SN-9").build();

    assertThat(before).isEqualTo(fingerprintOf("single"));
    assertThat(after).isEqualTo(fingerprintOf("two_sorted"));
  }

  @Test
  void labelsOutsideAsciiPrintableAreRefusedSoTheyNeverNeedNormalising() {
    assertThatThrownBy(() -> Fingerprint.builder().add("caf" + E_ACUTE, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+00E9");

    assertThatThrownBy(() -> Fingerprint.builder().add("has space", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+0020");
  }
}
