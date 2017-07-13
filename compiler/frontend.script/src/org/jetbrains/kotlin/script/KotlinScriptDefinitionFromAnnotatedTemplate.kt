/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.script

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.NameUtils
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions
import kotlin.script.dependencies.DependenciesResolver
import kotlin.script.dependencies.experimental.AsyncDependenciesResolver
import kotlin.script.templates.AcceptedAnnotations

open class KotlinScriptDefinitionFromAnnotatedTemplate(
        template: KClass<out Any>,
        providedResolver: DependenciesResolver? = null,
        providedScriptFilePattern: String? = null,
        val environment: Map<String, Any?>? = null
) : KotlinScriptDefinition(template) {

    val scriptFilePattern by lazy {
        providedScriptFilePattern
        ?: takeUnlessError { template.annotations.firstIsInstanceOrNull<kotlin.script.templates.ScriptTemplateDefinition>()?.scriptFilePattern }
        ?: takeUnlessError { template.annotations.firstIsInstanceOrNull<org.jetbrains.kotlin.script.ScriptTemplateDefinition>()?.scriptFilePattern }
        ?: DEFAULT_SCRIPT_FILE_PATTERN
    }

    override val dependencyResolver: DependenciesResolver by lazy {
        computeResolver(template, providedResolver)
    }

    private fun computeResolver(
            template: KClass<out Any>,
            providedResolver: DependenciesResolver?
    ): DependenciesResolver {
        return providedResolver ?:
               resolverFromAnnotation(template) ?:
               resolverFromLegacyAnnotation(template) ?:
               DependenciesResolver.NoDependencies
    }

    private fun resolverFromLegacyAnnotation(template: KClass<out Any>): DependenciesResolver? {
        val legacyDefAnn = takeUnlessError {
            template.annotations.firstIsInstanceOrNull<ScriptTemplateDefinition>()
        } ?: return null

        log.warn("[kts] Deprecated annotations on the script template are used, please update the provider")

        return instantiateResolver(legacyDefAnn.resolver)?.let(::LegacyPackageDependencyResolverWrapper)
    }

    private fun resolverFromAnnotation(template: KClass<out Any>): DependenciesResolver? {
        val defAnn = takeUnlessError {
            template.annotations.firstIsInstanceOrNull<kotlin.script.templates.ScriptTemplateDefinition>()
        } ?: return null

        val resolver = instantiateResolver(defAnn.resolver)
        return when (resolver) {
            is AsyncDependenciesResolver -> AsyncDependencyResolverWrapper(resolver)
            is DependenciesResolver -> resolver
            else -> resolver?.let(::ApiChangeDependencyResolverWrapper)
        }
    }

    private fun <T : Any> instantiateResolver(resolverClass: KClass<T>): T? {
        try {
            val constructorWithoutParameters = resolverClass.constructors.find { it.parameters.all { it.isOptional } }
            if (constructorWithoutParameters == null) {
                log.warn("[kts] ${resolverClass.qualifiedName} must have a constructor without parameters")
                return null
            }
            return constructorWithoutParameters.call()
        }
        catch (ex: ClassCastException) {
            log.warn("[kts] Script def error ${ex.message}")
            return null
        }
    }

    val samWithReceiverAnnotations: List<String>? by lazy {
        takeUnlessError { template.annotations.firstIsInstanceOrNull<kotlin.script.extensions.SamWithReceiverAnnotations>()?.annotations?.toList() }
        ?: takeUnlessError { template.annotations.firstIsInstanceOrNull<org.jetbrains.kotlin.script.SamWithReceiverAnnotations>()?.annotations?.toList() }
    }

    override val acceptedAnnotations: List<KClass<out Annotation>> by lazy {

        fun sameSignature(left: KFunction<*>, right: KFunction<*>): Boolean =
                left.name == right.name &&
                left.parameters.size == right.parameters.size &&
                left.parameters.zip(right.parameters).all {
                    it.first.kind == KParameter.Kind.INSTANCE ||
                    it.first.type == it.second.type
                }

        // DependenciesResolver::resolve, ScriptDependenciesResolver::resolve, AsyncDependenciesResolver::resolveAsync
        val resolveMethods = AsyncDependenciesResolver::class.memberFunctions.filter { it.name == "resolve" || it.name == "resolveAsync" }
        assert(resolveMethods.size == 3)
        dependencyResolver.unwrap()::class.memberFunctions
                .filter { function -> resolveMethods.any { sameSignature(function, it) } }
                .flatMap { it.annotations }
                .filterIsInstance<AcceptedAnnotations>()
                .flatMap { it.supportedAnnotationClasses.toList() }
                .distinctBy { it.qualifiedName }
    }

    override val name = template.simpleName!!

    override fun isScript(fileName: String): Boolean =
            scriptFilePattern.let { Regex(it).matches(fileName) }

    // TODO: implement other strategy - e.g. try to extract something from match with ScriptFilePattern
    override fun getScriptName(script: KtScript): Name = NameUtils.getScriptNameForFile(script.containingKtFile.name)

    override fun toString(): String = "KotlinScriptDefinitionFromAnnotatedTemplate - ${template.simpleName}"

    override val annotationsForSamWithReceivers: List<String>
        get() = samWithReceiverAnnotations ?: super.annotationsForSamWithReceivers

    private inline fun<T> takeUnlessError(reportError: Boolean = true, body: () -> T?): T? =
            try {
                body()
            }
            catch (ex: Throwable) {
                if (reportError) {
                    log.error("Invalid script template: " + template.qualifiedName, ex)
                }
                else {
                    log.warn("Invalid script template: " + template.qualifiedName, ex)
                }
                null
            }

    companion object {
        internal val log = Logger.getInstance(KotlinScriptDefinitionFromAnnotatedTemplate::class.java)
    }
}

interface DependencyResolverWrapper<T: kotlin.script.dependencies.ScriptDependenciesResolver> {
    val delegate: T
}

fun kotlin.script.dependencies.ScriptDependenciesResolver.unwrap(): kotlin.script.dependencies.ScriptDependenciesResolver {
    return if (this is DependencyResolverWrapper<*>) delegate else this
}