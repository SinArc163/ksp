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

package com.google.devtools.ksp.impl

/**
 * A namespace for constants in Kotlin such as special fully qualified names.
 */
internal object KotlinConstants {

    /**
     * The fully qualified name of Kotlin's [Array] type.
     */
    const val ARRAY_FQN = "kotlin.Array"

    /**
     * The fully qualified name of Kotlin's [BooleanArray] type.
     */
    const val ARRAY_BOOLEAN_FQN = "kotlin.BooleanArray"

    /**
     * The fully qualified name of Kotlin's [ByteArray] type.
     */
    const val ARRAY_BYTE_FQN = "kotlin.ByteArray"

    /**
     * The fully qualified name of Kotlin's [CharArray] type.
     */
    const val ARRAY_CHAR_FQN = "kotlin.CharArray"

    /**
     * The fully qualified name of Kotlin's [ShortArray] type.
     */
    const val ARRAY_SHORT_FQN = "kotlin.ShortArray"

    /**
     * The fully qualified name of Kotlin's [IntArray] type.
     */
    const val ARRAY_INT_FQN = "kotlin.IntArray"

    /**
     * The fully qualified name of Kotlin's [LongArray] type.
     */
    const val ARRAY_LONG_FQN = "kotlin.LongArray"

    /**
     * The fully qualified name of Kotlin's [FloatArray] type.
     */
    const val ARRAY_FLOAT_FQN = "kotlin.FloatArray"

    /**
     * The fully qualified name of Kotlin's [DoubleArray] type.
     */
    const val ARRAY_DOUBLE_FQN = "kotlin.DoubleArray"

    /**
     * The set of fully qualified names for Kotlin's primitive arrays.
     */
    val PRIMITIVE_ARRAY_FQ_NAMES = setOf(
        ARRAY_BOOLEAN_FQN,
        ARRAY_BYTE_FQN,
        ARRAY_CHAR_FQN,
        ARRAY_SHORT_FQN,
        ARRAY_INT_FQN,
        ARRAY_LONG_FQN,
        ARRAY_FLOAT_FQN,
        ARRAY_DOUBLE_FQN,
    )

    /**
     * The set of all array names in Kotlin, i.e., both [PRIMITIVE_ARRAY_FQ_NAMES] and [ARRAY_FQN].
     */
    val ALL_ARRAY_FQ_NAMES = PRIMITIVE_ARRAY_FQ_NAMES + ARRAY_FQN
}
