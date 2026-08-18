/**
 * Wire-format model types: license/machine/policy resources and the enums that describe their
 * server-side behavior ({@link sh.tamga.sdk.model.ValidationCode} and friends).
 *
 * <p><b>Partially implemented.</b> {@link sh.tamga.sdk.model.License}, {@link
 * sh.tamga.sdk.model.Machine}, {@link sh.tamga.sdk.model.LicenseFileClaims}, {@link
 * sh.tamga.sdk.model.LicenseScheme}, {@link sh.tamga.sdk.model.HeartbeatStatus}, {@link
 * sh.tamga.sdk.model.CanonicalJson} and {@link sh.tamga.sdk.model.TamgaJsonMapper} are real and
 * tested. {@link sh.tamga.sdk.model.ValidationCode} and {@link sh.tamga.sdk.model.Policy} are
 * still empty scaffolds, deferred with the rest of the HTTP-facing surface.
 *
 * <p>{@code License}/{@code Machine} model exactly the fields carried inside an offline file, not
 * the full API resource shape. Several enums here model server-declared values that are not
 * actually reachable yet -- read each type's class-level Javadoc before assuming a value will ever
 * appear on the wire.
 */
package sh.tamga.sdk.model;
