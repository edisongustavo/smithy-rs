/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators

import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientRustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumGeneratorContext
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumMemberModel
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumType
import software.amazon.smithy.rust.codegen.core.util.dq

/** Infallible enums have an `Unknown` variant and can't fail to parse */
data class InfallibleEnumType(
    val unknownVariantModule: RustModule,
) : EnumType() {
    companion object {
        /** Name of the generated unknown enum member name for enums with named members. */
        const val UnknownVariant = "Unknown"

        /** Name of the opaque struct that is inner data for the generated [UnknownVariant]. */
        const val UnknownVariantValue = "UnknownVariantValue"
    }

    override fun implFromForStr(context: EnumGeneratorContext): Writable = writable {
        rustTemplate(
            """
            impl #{From}<&str> for ${context.enumName} {
                fn from(s: &str) -> Self {
                    match s {
                        #{matchArms}
                    }
                }
            }
            """,
            "From" to RuntimeType.From,
            "matchArms" to writable {
                context.sortedMembers.forEach { member ->
                    rust("${member.value.dq()} => ${context.enumName}::${member.derivedName()},")
                }
                rust(
                    "other => ${context.enumName}::$UnknownVariant(#T(other.to_owned()))",
                    unknownVariantValue(context),
                )
            },
        )
    }

    override fun implFromStr(context: EnumGeneratorContext): Writable = writable {
        rust(
            """
            impl std::str::FromStr for ${context.enumName} {
                type Err = std::convert::Infallible;

                fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
                    Ok(${context.enumName}::from(s))
                }
            }
            """,
        )
    }

    override fun additionalDocs(context: EnumGeneratorContext): Writable = writable {
        renderForwardCompatibilityNote(context.enumName, context.sortedMembers, UnknownVariant, UnknownVariantValue)
    }

    override fun additionalEnumMembers(context: EnumGeneratorContext): Writable = writable {
        docs("`$UnknownVariant` contains new variants that have been added since this code was generated.")
        rust("$UnknownVariant(#T)", unknownVariantValue(context))
    }

    override fun additionalAsStrMatchArms(context: EnumGeneratorContext): Writable = writable {
        rust("${context.enumName}::$UnknownVariant(value) => value.as_str()")
    }

    private fun unknownVariantValue(context: EnumGeneratorContext): RuntimeType {
        return RuntimeType.forInlineFun(UnknownVariantValue, ClientRustModule.Types) {
            docs(
                """
                Opaque struct used as inner data for the `Unknown` variant defined in enums in
                the crate

                While this is not intended to be used directly, it is marked as `pub` because it is
                part of the enums that are public interface.
                """.trimIndent(),
            )
            context.enumMeta.render(this)
            rust("struct $UnknownVariantValue(pub(crate) String);")
            rustBlock("impl $UnknownVariantValue") {
                // The generated as_str is not pub as we need to prevent users from calling it on this opaque struct.
                rustBlock("pub(crate) fn as_str(&self) -> &str") {
                    rust("&self.0")
                }
            }
        }
    }

    /**
     * Generate the rustdoc describing how to write a match expression against a generated enum in a
     * forward-compatible way.
     */
    private fun RustWriter.renderForwardCompatibilityNote(
        enumName: String, sortedMembers: List<EnumMemberModel>,
        unknownVariant: String, unknownVariantValue: String,
    ) {
        docs(
            """
            When writing a match expression against `$enumName`, it is important to ensure
            your code is forward-compatible. That is, if a match arm handles a case for a
            feature that is supported by the service but has not been represented as an enum
            variant in a current version of SDK, your code should continue to work when you
            upgrade SDK to a future version in which the enum does include a variant for that
            feature.
            """.trimIndent(),
        )
        docs("")
        docs("Here is an example of how you can make a match expression forward-compatible:")
        docs("")
        docs("```text")
        rust("/// ## let ${enumName.lowercase()} = unimplemented!();")
        rust("/// match ${enumName.lowercase()} {")
        sortedMembers.mapNotNull { it.name() }.forEach { member ->
            rust("///     $enumName::${member.name} => { /* ... */ },")
        }
        rust("""///     other @ _ if other.as_str() == "NewFeature" => { /* handles a case for `NewFeature` */ },""")
        rust("///     _ => { /* ... */ },")
        rust("/// }")
        docs("```")
        docs(
            """
            The above code demonstrates that when `${enumName.lowercase()}` represents
            `NewFeature`, the execution path will lead to the second last match arm,
            even though the enum does not contain a variant `$enumName::NewFeature`
            in the current version of SDK. The reason is that the variable `other`,
            created by the `@` operator, is bound to
            `$enumName::$unknownVariant($unknownVariantValue("NewFeature".to_owned()))`
            and calling `as_str` on it yields `"NewFeature"`.
            This match expression is forward-compatible when executed with a newer
            version of SDK where the variant `$enumName::NewFeature` is defined.
            Specifically, when `${enumName.lowercase()}` represents `NewFeature`,
            the execution path will hit the second last match arm as before by virtue of
            calling `as_str` on `$enumName::NewFeature` also yielding `"NewFeature"`.
            """.trimIndent(),
        )
        docs("")
        docs(
            """
            Explicitly matching on the `$unknownVariant` variant should
            be avoided for two reasons:
            - The inner data `$unknownVariantValue` is opaque, and no further information can be extracted.
            - It might inadvertently shadow other intended match arms.
            """.trimIndent(),
        )
    }
}

class ClientEnumGenerator(codegenContext: CodegenContext, shape: StringShape) :
    EnumGenerator(codegenContext.model, codegenContext.symbolProvider, shape, InfallibleEnumType(ClientRustModule.Types))