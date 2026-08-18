/**
 * Offline license/machine file parsing, signature verification, and decryption -- the core
 * offline-verification payload format every Tamga SDK either implements or binds to.
 *
 * <p>Implemented and tested. {@link sh.tamga.sdk.checkout.LicenseFile} handles {@code .lic} files
 * (offline format v2 only: {@code alg} ending in {@code +v2}, signed {@code iat}/{@code exp}/
 * {@code jti}/{@code kid} claims, {@code exp} enforced with a 60-second clock-skew tolerance);
 * {@link sh.tamga.sdk.checkout.MachineFile} handles {@code .machine} files, dispatching the
 * signature check on a caller-supplied {@link sh.tamga.sdk.model.LicenseScheme}. Both derive their
 * AES-256-GCM key with HKDF-SHA256 ({@link sh.tamga.sdk.crypto.Hkdf}), using different salt/{@code
 * info} per format.
 *
 * <p><b>security-reviewer MANDATORY</b> on every change in this package -- this is the SDK's core
 * offline-verification payload; correctness here has cross-SDK blast radius (every other Tamga SDK
 * implements or binds to the same format). See this repository's {@code CLAUDE.md}.
 */
package sh.tamga.sdk.checkout;
