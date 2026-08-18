/**
 * Error model.
 *
 * <p>{@link sh.tamga.sdk.error.TamgaCheckoutException} and its nested subtypes are implemented:
 * they cover every way parsing, verifying or decrypting an already-issued offline file or proof
 * can fail, and are what the {@code checkout}/{@code proof} packages throw.
 *
 * <p>{@link sh.tamga.sdk.error.TamgaError} -- the JSON:API error object, its exception wrapper,
 * and the {@code code} to exception-type dispatch table -- is still an empty scaffold, deferred
 * with the rest of the HTTP-facing surface.
 */
package sh.tamga.sdk.error;
