package dev.min.agent.kernel

import dev.min.agent.spi.*
import dev.min.agent.policy.PolicyContext
import dev.min.agent.policy.credentialPath
import dev.min.agent.protocol.PermissionMode
import kotlinx.coroutines.flow.filter

internal fun credentialSearch(delegate: Search, paths: Set<GuestPath>, canonical: (GuestPath)->GuestPath): Search {
    val context=PolicyContext(PermissionMode.Default,credentialPaths=paths,canonicalPath=canonical)
    return object : Search {
        override fun grep(query: GrepQuery) = delegate.grep(query.copy(exclude={ query.exclude(it) || credentialPath(it,context) }))
            .filter { !credentialPath(it.path,context) }
        override fun glob(query: GlobQuery) = delegate.glob(query.copy(exclude={ query.exclude(it) || credentialPath(it,context) }))
            .filter { !credentialPath(it,context) }
    }
}
