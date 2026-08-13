package sh.tamga.sdk.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM open/seal over the JDK's built-in {@code javax.crypto.Cipher} ("AES/GCM/NoPadding",
 * available since JDK 8) -- algorithm only, never derives the key itself. See {@link Hkdf} for the
 * two distinct, non-interchangeable key-derivation paths (license checkout vs. machine checkout)
 * that feed this class -- same HKDF-SHA256 primitive, different salt/info per format.
 */
public final class AesGcm {

  /** Standard AES-GCM nonce length in bytes. */
  public static final int NONCE_LENGTH = 12;

  /** Standard AES-GCM authentication tag length in bytes. */
  public static final int TAG_LENGTH = 16;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";

  private AesGcm() {
  }

  /**
   * Decrypts and authenticates a ciphertext. Throws {@link AuthenticationFailedException} (fails
   * closed) on tag mismatch -- never returns garbage plaintext for a tampered input. Throws
   * {@link MalformedAesGcmInputException} for a structurally invalid nonce/tag/key, distinguishable
   * from an authentication failure since it means the caller passed invalid input, not that the
   * cipher cryptographically rejected well-formed input.
   */
  public static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext, byte[] tag) {
    if (nonce.length != NONCE_LENGTH || tag.length != TAG_LENGTH) {
      throw new MalformedAesGcmInputException(
          "Nonce must be " + NONCE_LENGTH + " bytes and tag must be " + TAG_LENGTH + " bytes.");
    }
    byte[] ciphertextAndTag = concat(ciphertext, tag);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(
          TAG_LENGTH * Byte.SIZE, nonce));
      return cipher.doFinal(ciphertextAndTag);
    } catch (AEADBadTagException e) {
      throw new AuthenticationFailedException(
          "AES-GCM authentication failed -- wrong key, wrong nonce, or a tampered ciphertext/tag.",
          e);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new MalformedAesGcmInputException("Malformed AES-GCM input: " + e.getMessage(), e);
    }
  }

  /**
   * Encrypts and authenticates a plaintext, producing ciphertext and a separate authentication tag.
   */
  public static SealedData seal(byte[] key, byte[] nonce, byte[] plaintext) {
    if (nonce.length != NONCE_LENGTH) {
      throw new MalformedAesGcmInputException("Nonce must be " + NONCE_LENGTH + " bytes.");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(
          TAG_LENGTH * Byte.SIZE, nonce));
      byte[] ciphertextAndTag = cipher.doFinal(plaintext);
      int ciphertextLength = ciphertextAndTag.length - TAG_LENGTH;
      byte[] ciphertext = new byte[ciphertextLength];
      byte[] tag = new byte[TAG_LENGTH];
      System.arraycopy(ciphertextAndTag, 0, ciphertext, 0, ciphertextLength);
      System.arraycopy(ciphertextAndTag, ciphertextLength, tag, 0, TAG_LENGTH);
      return new SealedData(ciphertext, tag);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new MalformedAesGcmInputException("Malformed AES-GCM input: " + e.getMessage(), e);
    }
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] out = new byte[first.length + second.length];
    System.arraycopy(first, 0, out, 0, first.length);
    System.arraycopy(second, 0, out, first.length, second.length);
    return out;
  }

  /** Ciphertext and its separate authentication tag, as produced by {@link #seal}. */
  public static final class SealedData {
    private final byte[] ciphertext;
    private final byte[] tag;

    SealedData(byte[] ciphertext, byte[] tag) {
      this.ciphertext = ciphertext;
      this.tag = tag;
    }

    /** Returns a copy of the ciphertext, without the authentication tag. */
    public byte[] ciphertext() {
      return ciphertext.clone();
    }

    /** Returns a copy of the separate authentication tag. */
    public byte[] tag() {
      return tag.clone();
    }
  }

  /** Base type for {@link #open}/{@link #seal} failures. */
  public abstract static class AesGcmException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AesGcmException(String message) {
      super(message);
    }

    AesGcmException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Authentication failed -- wrong key, wrong nonce, or a tampered ciphertext/tag. */
  public static final class AuthenticationFailedException extends AesGcmException {
    private static final long serialVersionUID = 1L;

    AuthenticationFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The nonce, tag, or key was structurally invalid -- distinct from a cryptographic rejection. */
  public static final class MalformedAesGcmInputException extends AesGcmException {
    private static final long serialVersionUID = 1L;

    MalformedAesGcmInputException(String message) {
      super(message);
    }

    MalformedAesGcmInputException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
