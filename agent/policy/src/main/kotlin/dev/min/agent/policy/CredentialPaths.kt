package dev.min.agent.policy

import dev.min.agent.spi.GuestPath

/** 宿主给文件名或末尾星号前缀；模型给 glob 时保守比较字面前缀，不能让通配绕过凭证边界。 */
fun credentialPath(path: GuestPath, context: PolicyContext, includeAncestors: Boolean = false): Boolean {
    if (context.credentialPaths.isEmpty()) return false
    val literal=path.value.takeWhile { it !in "*?[" }
    val glob=literal!=path.value
    val raw=if(glob) GuestPath(literal.substringBeforeLast('/',"/").ifEmpty { "/" }) else path
    val resolved=context.canonicalPath(raw).value
    val candidate=if(glob) resolved.trimEnd('/')+"/"+literal.substringAfterLast('/') else resolved
    val normalized=normalizePolicyPath(candidate)
    return context.credentialPaths.any { spec ->
        val prefix=normalizePolicyPath(spec.value.removeSuffix("*"))
        val protected=if(spec.value.endsWith('*')) normalized.startsWith(prefix) else normalized==prefix || normalized.startsWith("$prefix/")
        protected || ((glob || includeAncestors) && (prefix.startsWith(normalized) && (glob || normalized=="/" || prefix.startsWith(normalized.trimEnd('/')+"/"))))
    }
}
