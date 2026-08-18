/**
 * Public API surface of the Tamga Java SDK: {@link sh.tamga.sdk.TamgaClient}, the top-level
 * entry point, and {@link sh.tamga.sdk.Transport}, the hand-rolled OkHttp-based HTTP layer.
 *
 * <p><b>STUB -- scaffolding only.</b> Neither type has any method yet, so this SDK cannot talk to
 * the API. What does work today lives in {@link sh.tamga.sdk.checkout} (offline {@code .lic} /
 * {@code .machine} files) and {@link sh.tamga.sdk.proof} (machine offline proofs); start there.
 *
 * <p>See this repository's {@code CLAUDE.md} for the crypto-boundary rule that keeps this package
 * free of direct cryptographic calls -- all of it belongs in {@link sh.tamga.sdk.crypto}, reached
 * only through the {@code checkout}/{@code proof} composition layers.
 */
package sh.tamga.sdk;
