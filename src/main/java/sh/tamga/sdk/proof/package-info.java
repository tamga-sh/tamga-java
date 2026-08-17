/**
 * Machine offline proof (air-gapped verification) -- a lighter-weight alternative to full checkout
 * for periodic "prove this machine is still valid" pings in air-gapped environments.
 *
 * <p>Proof parsing and verification are implemented and tested in {@link
 * sh.tamga.sdk.proof.OfflineProof}. Obtaining a proof from the server is not: that call belongs to
 * the HTTP-facing surface, which is still a stub.
 *
 * <p><b>security-reviewer MANDATORY</b> on every change in this package -- the signature covers a
 * server-produced JSON serialization; reproducing it with different field order silently breaks
 * verification, which is exactly the kind of bug that only shows up in production against real
 * air-gapped deployments. See this repository's {@code CLAUDE.md}.
 */
package sh.tamga.sdk.proof;
