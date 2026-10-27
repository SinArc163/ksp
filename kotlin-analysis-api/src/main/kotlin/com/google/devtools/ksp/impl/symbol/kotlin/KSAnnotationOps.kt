/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
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

package com.google.devtools.ksp.impl.symbol.kotlin

import com.google.devtools.ksp.InternalKSPException
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.impl.KotlinConstants
import com.google.devtools.ksp.impl.recordClassReferenceLookup
import com.google.devtools.ksp.impl.symbol.java.KSValueArgumentLiteImpl
import com.google.devtools.ksp.impl.symbol.java.calcValue
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.Origin
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.impl.compiled.ClsClassImpl
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.annotations.KaNamedAnnotationValue
import org.jetbrains.kotlin.analysis.api.impl.base.annotations.KaBaseNamedAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.name.ClassId

/**
 * Contains helper functions for implementing [KSAnnotation].
 */
internal object KSAnnotationOps {

    /**
     * Finds and synthesizes an unassigned vararg argument if one was omitted at the annotation use site.
     *
     * In Kotlin, an unsupplied vararg parameter defaults to an empty array (represented as an empty list).
     * If the annotation defines an unassigned vararg parameter that was omitted from [presentArgs] and does
     * not have an explicit default value, this returns a synthetic [KSValueArgument] containing an empty list.
     *
     * For Java-defined annotations applied in Kotlin, delegates to [resolveAbsentJavaSourceVararg] because
     * Java annotations lack a primary constructor and vararg modifiers.
     *
     * @param annotationApplication The [KSAnnotation] call site / instance to which this vararg argument belongs.
     * @param declaration The [KSClassDeclaration] of the annotation, or `null` if unresolvable.
     * @param presentArgs The list of arguments present at the call site.
     * @return A synthetic [KSValueArgument] with an empty list if a vararg parameter was omitted, or `null` otherwise.
     */
    @JvmStatic
    fun resolveAbsentVararg(
        annotationApplication: KSAnnotation,
        declaration: KSClassDeclaration?,
        presentArgs: List<KaNamedAnnotationValue>
    ): KSValueArgument? {
        if (declaration == null) {
            return null
        }

        // Special case: Java source annotations in Kotlin source.
        // They behave differently since the primary constructor is null.
        if (declaration.origin == Origin.JAVA) {
            return resolveAbsentJavaSourceVararg(annotationApplication, declaration, presentArgs)
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
            parent = annotationApplication,
            origin = Origin.SYNTHETIC,
            location = annotationApplication.location
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
     * @param annotationApplication The [KSAnnotation] call site / instance to which this vararg argument belongs.
     * @param declaration The [KSClassDeclaration] of the Java annotation (must have [Origin.JAVA]).
     * @param presentArgs The list of arguments present at the call site.
     * @return A synthetic [KSValueArgument] for `"value"` with an empty list, or `null` if not applicable or already present.
     */
    @JvmStatic
    private fun resolveAbsentJavaSourceVararg(
        annotationApplication: KSAnnotation,
        declaration: KSClassDeclaration,
        presentArgs: List<KaNamedAnnotationValue>
    ): KSValueArgument? {
        // Precondition: The declaration must come from a Java source
        if (declaration.origin != Origin.JAVA) {
            throw InternalKSPException(
                message = "Expected Origin.JAVA in findAbsentJavaSourceVararg, but got ${declaration.origin}",
                location = annotationApplication.location,
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
            parent = annotationApplication,
            origin = Origin.SYNTHETIC,
            location = annotationApplication.location
        )
    }

    /**
     * Returns any default arguments defined by the annotation that were not explicitly supplied at the call site.
     *
     * @param ksAnnotation The [KSAnnotation] call site / instance whose default arguments are being retrieved.
     * @param presentArgs The list of arguments explicitly provided at the call site.
     * @return A list of [KSValueArgument]s representing default values for all parameters omitted from [presentArgs].
     */
    @JvmStatic
    fun resolveAbsentDefaultArguments(
        ksAnnotation: KSAnnotation,
        presentArgs: List<KSValueArgument>
    ): List<KSValueArgument> {
        val presentNames = presentArgs.mapNotNull { it.name?.asString() }
        return ksAnnotation.defaultArguments.filter { arg ->
            arg.name?.asString() !in presentNames
        }
    }

    /**
     * Resolves and returns all default argument values defined on an annotation declaration.
     *
     * For Java source annotations ([PsiClass]), default values are extracted from [PsiAnnotationMethod.defaultValue].
     * For Kotlin annotations, default values are retrieved from the primary constructor value parameters,
     * and class reference lookups are recorded for incremental processing when default values contain class literals.
     *
     * @param ksAnnotation The [KSAnnotation] call site / instance to which the resolved default arguments will belong.
     * @param classId The [ClassId] of the annotation class declaration, or `null`.
     * @return A list of [KSValueArgument]s representing all default arguments defined by the annotation.
     */
    @OptIn(KaImplementationDetail::class)
    @JvmStatic
    fun resolveDefaultArguments(ksAnnotation: KSAnnotation, classId: ClassId?): List<KSValueArgument> {
        if (classId == null) return emptyList()
        return analyze {
            classId.toKtClassSymbol()?.let { symbol ->
                if (
                    symbol.origin == KaSymbolOrigin.JAVA_SOURCE && symbol.psi != null &&
                    symbol.psi !is ClsClassImpl && symbol.psi is PsiClass
                ) {
                    (symbol.psi as PsiClass).allMethods.filterIsInstance<PsiAnnotationMethod>()
                        .mapNotNull { annoMethod ->
                            annoMethod.defaultValue?.let { value ->
                                KSValueArgumentLiteImpl(
                                    KSNameImpl.getCached(annoMethod.name),
                                    calcValue(value, ksAnnotation),
                                    ksAnnotation,
                                    Origin.SYNTHETIC,
                                    value.toLocation()
                                )
                            }
                        }
                } else {
                    symbol.memberScope.constructors.singleOrNull()?.let {
                        it.valueParameters.mapNotNull { valueParameterSymbol ->
                            if (!valueParameterSymbol.hasDefaultValue) return@mapNotNull null
                            val constantValue = valueParameterSymbol.getDefaultValue() ?: return@mapNotNull null
                            if (constantValue is KaAnnotationValue.ClassLiteralValue) {
                                ksAnnotation.findParentDeclaration()?.let { decl ->
                                    recordClassReferenceLookup(constantValue.type, decl)
                                }
                            }
                            KSValueArgumentImpl.getCached(
                                KaBaseNamedAnnotationValue(
                                    valueParameterSymbol.name,
                                    constantValue
                                ),
                                ksAnnotation,
                                Origin.SYNTHETIC
                            )
                        }
                    }
                }
            } ?: emptyList()
        }
    }
}
