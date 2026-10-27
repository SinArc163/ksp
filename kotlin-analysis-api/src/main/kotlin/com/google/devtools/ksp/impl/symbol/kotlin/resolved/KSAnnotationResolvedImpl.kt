/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
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
package com.google.devtools.ksp.impl.symbol.kotlin.resolved

import com.google.devtools.ksp.InternalKSPException
import com.google.devtools.ksp.common.IdKeyPair
import com.google.devtools.ksp.common.KSObjectCache
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.impl.KotlinConstants
import com.google.devtools.ksp.impl.symbol.java.KSValueArgumentLiteImpl
import com.google.devtools.ksp.impl.symbol.kotlin.KSValueArgumentImpl
import com.google.devtools.ksp.impl.symbol.kotlin.analyze
import com.google.devtools.ksp.impl.symbol.kotlin.getAbsentDefaultArguments
import com.google.devtools.ksp.impl.symbol.kotlin.getDefaultArguments
import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.NonExistLocation
import com.google.devtools.ksp.symbol.Origin
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaNamedAnnotationValue
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.ALL
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.FIELD
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.FILE
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.PROPERTY
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.PROPERTY_DELEGATE_FIELD
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.PROPERTY_GETTER
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.PROPERTY_SETTER
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.RECEIVER
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.SETTER_PARAMETER
import org.jetbrains.kotlin.psi.KtFile

/**
 * A resolved [KSAnnotation] backed directly by an Analysis API [KaAnnotation],
 * rather than a source PSI node ([KtAnnotationEntry][org.jetbrains.kotlin.psi.KtAnnotationEntry] or
 * [PsiAnnotation][com.intellij.psi.PsiAnnotation]).
 *
 * This implementation is used when annotations are resolved semantically or lack direct source PSI,
 * such as:
 * - Annotations loaded from compiled bytecode/libraries (binary symbols).
 * - Annotations on resolved types or type references.
 * - Nested annotations passed as arguments inside another annotation.
 * - Synthesized annotations (such as `@ExtensionFunctionType`).
 * - Annotations mapped via use-site targets on accessors or backing fields.
 *
 * Because there is no direct source PSI annotation entry, [location] is always [NonExistLocation].
 *
 * @param annotationApplication The underlying resolved Analysis API [KaAnnotation] representation.
 * @param parent The parent AST node on which this annotation is applied (e.g., declaration, type reference, or value argument).
 * @param origin The source or binary origin of this annotation (e.g., [Origin.KOTLIN], [Origin.JAVA], [Origin.KOTLIN_LIB], [Origin.JAVA_LIB], or [Origin.SYNTHETIC]).
 */
class KSAnnotationResolvedImpl private constructor(
    private val annotationApplication: KaAnnotation,
    override val parent: KSNode?,
    override val origin: Origin,
) : KSAnnotation {

    companion object :
        KSObjectCache<IdKeyPair<KaAnnotation, KSNode?>, KSAnnotationResolvedImpl>() {

        /**
         * Returns a cached [KSAnnotationResolvedImpl] for the given [KaAnnotation].
         *
         * @param annotationApplication The underlying resolved Analysis API [KaAnnotation] representation.
         * @param parent The parent AST node on which this annotation is applied. Defaults to `null`.
         * @param origin The origin of this annotation. If not specified, defaults to `parent?.origin` or [Origin.SYNTHETIC].
         */
        fun getCached(annotationApplication: KaAnnotation, parent: KSNode? = null, origin: Origin? = parent?.origin) =
            cache.getOrPut(IdKeyPair(annotationApplication, parent)) {
                KSAnnotationResolvedImpl(annotationApplication, parent, origin ?: Origin.SYNTHETIC)
            }
    }

    override val annotationType: KSTypeReference by lazy {
        analyze {
            KSTypeReferenceResolvedImpl.getCached(
                buildClassType(annotationApplication.classId!!),
                parent = this@KSAnnotationResolvedImpl
            )
        }
    }
    override val arguments: List<KSValueArgument> by lazy {
        val presentArgs = annotationApplication.arguments.map { arg ->
            val argOrigin = if (origin == Origin.SYNTHETIC) {
                arg.expression.sourcePsi?.containingFile?.let { containingFile ->
                    if (containingFile is KtFile) Origin.KOTLIN else Origin.JAVA
                } ?: Origin.JAVA_LIB // FIXME: how to tell from KOTLIN_LIB?
            } else origin
            KSValueArgumentImpl.getCached(arg, this, argOrigin)
        }
        presentArgs +
            getAbsentDefaultArguments(presentArgs) +
            listOfNotNull(findAbsentVarargs(annotationApplication.arguments))
    }

    private val annotationDeclaration: KSClassDeclaration? by lazy {
        annotationType.resolve().declaration as? KSClassDeclaration
    }

    /**
     * Finds and synthesizes an unassigned vararg argument if one was omitted at the annotation use site.
     *
     * In Kotlin, an unsupplied vararg parameter defaults to an empty array (represented as an empty list).
     * If the annotation defines an unassigned vararg parameter that was omitted from [presentArgs] and does
     * not have an explicit default value, this returns a synthetic [KSValueArgument] containing an empty list.
     *
     * For Java-defined annotations applied in Kotlin, delegates to [findAbsentJavaSourceVararg] because
     * Java annotations lack a primary constructor and vararg modifiers.
     *
     * @param presentArgs The list of arguments present at the call site.
     * @return A synthetic [KSValueArgument] with an empty list if a vararg parameter was omitted, or `null` otherwise.
     */
    private fun findAbsentVarargs(presentArgs: List<KaNamedAnnotationValue>): KSValueArgument? {
        // Special case: Java source annotations in Kotlin source.
        // They behave differently since the primary constructor is null.
        val declaration = annotationDeclaration ?: return null
        if (declaration.origin == Origin.JAVA) {
            return findAbsentJavaSourceVararg(declaration, presentArgs)
        }

        // 0. Check for full application and do early return
        val annotationParameters = declaration.primaryConstructor?.parameters
        if (annotationParameters?.size == presentArgs.size) {
            return null
        }

        // 1. Obtain a vararg parameter if it exists.
        // If it has a default value, it has been returned by `getAbsentDefaultArgument` so do early return.
        val varargParameter = annotationParameters?.find { it.isVararg && !it.hasDefault } ?: return null

        // 2. Check if the vararg was in the present arguments. Early return if it is, since it's not absent.
        val presentArgsVararg = presentArgs.find { varargParameter.name == KSNameImpl.getCached(it.name.asString()) }

        if (presentArgsVararg != null) {
            return null
        }

        // 3. The parameter is missing so return a singleton list containing empty list value.
        return KSValueArgumentLiteImpl(
            name = varargParameter.name,
            value = emptyList<Any?>(),
            parent = this,
            origin = Origin.SYNTHETIC,
            location = location
        )
    }

    /**
     * Finds and synthesizes an omitted array-typed `value` argument for Java source annotations.
     *
     * In Java annotations, an attribute like `String[] value();` behaves like a vararg parameter when applied
     * at a Kotlin call site (e.g., allowing `@MyAnno` with no arguments). Because Java annotations in KSP do not
     * expose a Kotlin primary constructor or `vararg` parameter flags, this method inspects the Java declaration's
     * declared properties. If a property named `"value"` has an array type (object or primitive array) and was
     * omitted at the call site, a synthetic [KSValueArgument] containing an empty list is created.
     *
     * @param declaration The [KSClassDeclaration] of the Java annotation (must have [Origin.JAVA]).
     * @param presentArgs The list of arguments present at the call site.
     * @return A synthetic [KSValueArgument] for `"value"` with an empty list, or `null` if not applicable or already present.
     */
    private fun findAbsentJavaSourceVararg(
        declaration: KSClassDeclaration,
        presentArgs: List<KaNamedAnnotationValue>
    ): KSValueArgument? {
        // Precondition: The declaration must come from a Java source
        if (declaration.origin != Origin.JAVA) {
            throw InternalKSPException(
                message = "Expected Origin.JAVA in findAbsentJavaSourceVararg, but got $origin",
                location = location,
                originatingClass = javaClass
            )
        }

        val parameters = declaration.getAllProperties().toList()

        // Early return if the annotation is fully applied.
        if (parameters.size == presentArgs.size) {
            return null
        }

        // Early return if vararg parameter is present. Regardless of the type, a Java vararg parameter
        // must have the name "value", so if it exists at the call site we can safely return null here.
        val valueName = KSNameImpl.getCached("value")
        val varargParameterIsPresentAtCallSite = presentArgs.find { it.name == valueName } != null

        if (varargParameterIsPresentAtCallSite) {
            return null
        }

        val maybeVarargMember = parameters.find { it.simpleName == valueName } ?: return null

        // Check if the type of the parameter is an array type
        val parameterTypeDeclaration =
            maybeVarargMember.type.resolve().declaration as? KSClassDeclaration ?: return null

        val fqn = parameterTypeDeclaration.qualifiedName?.asString() ?: return null
        val isArrayType = fqn in KotlinConstants.ALL_ARRAY_FQ_NAMES

        if (!isArrayType) {
            return null
        }

        return KSValueArgumentLiteImpl(
            name = valueName,
            value = emptyList<Any?>(),
            parent = this,
            origin = Origin.SYNTHETIC,
            location = location
        )
    }

    override val defaultArguments: List<KSValueArgument> by lazy {
        getDefaultArguments(annotationApplication.classId)
    }

    override val shortName: KSName by lazy {
        KSNameImpl.getCached(annotationApplication.classId!!.shortClassName.asString())
    }

    override val useSiteTarget: AnnotationUseSiteTarget? by lazy {
        // Do not use compiler hard-coded use-site target.
        // FIXME: use origin after it is fixed.
        if (parent?.origin == Origin.KOTLIN_LIB || parent?.origin == Origin.JAVA_LIB)
            return@lazy null

        when (annotationApplication.useSiteTarget) {
            null -> null
            FILE -> AnnotationUseSiteTarget.FILE
            PROPERTY -> AnnotationUseSiteTarget.PROPERTY
            FIELD -> AnnotationUseSiteTarget.FIELD
            PROPERTY_GETTER -> AnnotationUseSiteTarget.GET
            PROPERTY_SETTER -> AnnotationUseSiteTarget.SET
            RECEIVER -> AnnotationUseSiteTarget.RECEIVER
            CONSTRUCTOR_PARAMETER -> AnnotationUseSiteTarget.PARAM
            SETTER_PARAMETER -> AnnotationUseSiteTarget.SETPARAM
            PROPERTY_DELEGATE_FIELD -> AnnotationUseSiteTarget.DELEGATE
            ALL -> AnnotationUseSiteTarget.ALL
        }
    }

    override val location: Location by lazy {
        NonExistLocation
    }

    override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R {
        return visitor.visitAnnotation(this, data)
    }

    override fun toString(): String {
        return "@${shortName.asString()}"
    }
}
