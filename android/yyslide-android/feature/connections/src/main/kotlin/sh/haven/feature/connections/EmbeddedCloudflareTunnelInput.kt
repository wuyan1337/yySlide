package sh.haven.feature.connections

/**
 * Captures the Cloudflare Tunnel transport state from the SSH profile
 * editor (GH #154). Passed alongside the [sh.haven.core.data.db.entities.ConnectionProfile]
 * to the save callback; null means "no embedded Cloudflare tunnel — drop
 * any existing one owned by this profile".
 *
 * Wired into the inline transport UI by [ConnectionEditDialog]; persisted
 * by [ConnectionsViewModel.saveProfileWithEmbeddedCloudflareTunnel] into a
 * hidden [sh.haven.core.data.db.entities.TunnelConfig] row whose
 * `ownerProfileId` matches the profile.
 */
data class EmbeddedCloudflareTunnelInput(
    /** Tunnel published hostname — typically the same string as the SSH profile's `host`. */
    val hostname: String,
    /** Optional Cloudflare Access team domain. Blank for unprotected routes. */
    val teamDomain: String,
    /** Cached Access JWT. Blank for unprotected routes. */
    val jwt: String,
    /** Unix epoch seconds when the JWT expires. 0 if no JWT. */
    val jwtExpiresAt: Long,
    /** Bastion-mode `Cf-Access-Jump-Destination` (`host:port`). Blank to omit. */
    val jumpDestination: String,
)
