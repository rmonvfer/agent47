package co.agentmode.agent47.app

import org.graalvm.nativeimage.hosted.Feature

public class KotlinCompilerCompatibilityFeature : Feature {
    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        COMPATIBILITY_CLASSES.forEach { className ->
            val compatibilityClass = access.findClassByName(className)
            access.registerAsUsed(compatibilityClass)
            compatibilityClass.declaredFields.forEach(access::registerAsAccessed)
        }
    }

    private companion object {
        private val COMPATIBILITY_CLASSES = listOf(
            "org.jetbrains.kotlin.load.java.SpecialGenericSignatures\$TypeSafeBarrierDescription",
            "org.jetbrains.kotlin.load.java.SpecialGenericSignatures\$TypeSafeBarrierDescription\$MAP_GET_OR_DEFAULT",
        )
    }
}
