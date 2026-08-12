/**
 * Native Java implementations of the cryptographic primitives Tamga's offline verification needs:
 * Ed25519 verify, AES-256-GCM open/seal, HKDF-SHA256 derive, ECDSA-P256 verify, and RSA-2048
 * PKCS#1 v1.5/PSS verify. JDK built-ins cover everything except Ed25519 (see {@link
 * sh.tamga.sdk.crypto.Ed25519}'s Javadoc for why that one goes through BouncyCastle instead).
 *
 * <p>This package, {@code checkout}, and {@code proof} are the only packages allowed to import
 * from here directly -- see this repository's {@code CLAUDE.md} "Crypto Architecture" section.
 */
package sh.tamga.sdk.crypto;
