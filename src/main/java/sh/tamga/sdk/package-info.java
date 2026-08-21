/**
 * Public API surface of the Tamga Java SDK: {@link sh.tamga.sdk.TamgaClient}, the top-level
 * entry point, and {@link sh.tamga.sdk.Transport}, the hand-rolled OkHttp-based HTTP layer.
 *
 * <p>Offline verification needs no client at all and lives in {@link sh.tamga.sdk.checkout}
 * (offline {@code .lic} / {@code .machine} files) and {@link sh.tamga.sdk.proof} (machine offline
 * proofs).
 *
 * <p><b>Authentication is enforced server-side</b> on every endpoint this package calls. For the
 * default {@link sh.tamga.sdk.AuthTransport#licenseKey} transport there is a second condition
 * beyond holding a valid key: the license's policy must set {@code authentication_strategy} to
 * {@code LICENSE} or {@code MIXED}. It defaults to {@code TOKEN}, under which every call answers
 * {@code 401 LICENSE_NOT_ALLOWED} no matter how correct the key is.
 *
 * <p>See this repository's {@code CLAUDE.md} for the crypto-boundary rule that keeps this package
 * free of direct cryptographic calls -- all of it belongs in {@link sh.tamga.sdk.crypto}, reached
 * only through the {@code checkout}/{@code proof} composition layers.
 */
package sh.tamga.sdk;
