package co.agentmode.agent47.app

import java.math.BigInteger

internal val RELEASE_VERSION_PATTERN = Regex(
    "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
        "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
)

internal fun compareReleaseVersions(left: String, right: String): Int {
    return parseReleaseVersion(left).compareTo(parseReleaseVersion(right))
}

private fun parseReleaseVersion(version: String): SemanticVersion {
    val match = RELEASE_VERSION_PATTERN.matchEntire(version)
        ?: error("invalid release version: $version")
    return SemanticVersion(
        major = BigInteger(match.groupValues[1]),
        minor = BigInteger(match.groupValues[2]),
        patch = BigInteger(match.groupValues[3]),
        prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.'),
    )
}

private data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>?,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease == null) return if (other.prerelease == null) 0 else 1
        if (other.prerelease == null) return -1

        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val comparison = comparePrereleaseIdentifier(prerelease[index], other.prerelease[index])
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }
}

private fun comparePrereleaseIdentifier(left: String, right: String): Int {
    val leftNumber = left.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
    val rightNumber = right.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}
