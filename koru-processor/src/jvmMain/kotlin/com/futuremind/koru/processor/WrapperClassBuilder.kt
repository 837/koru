package com.futuremind.koru.processor

import com.futuremind.koru.FlowWrapper
import com.futuremind.koru.ScopeProvider
import com.futuremind.koru.SuspendWrapper
import com.squareup.kotlinpoet.*


class WrapperClassBuilder(
    originalTypeName: ClassName,
    originalTypeSpec: TypeSpec,
    private val newTypeName: String,
    private val originalToGeneratedInterface: OriginalToGeneratedInterface?,
    private val scopeProviderMemberName: MemberName?
) {

    companion object {
        private const val WRAPPED_PROPERTY_NAME = "wrapped"
        private const val SCOPE_PROVIDER_PROPERTY_NAME = "scopeProvider"
    }

    private val constructorSpec = FunSpec.constructorBuilder()
        .addParameter(WRAPPED_PROPERTY_NAME, originalTypeName)
        .addParameter(
            ParameterSpec
                .builder(SCOPE_PROVIDER_PROPERTY_NAME, ScopeProvider::class.asTypeName()
                    .copy(nullable = true))
                .build()
        )
        .build()

    private val secondaryConstructorSpec = FunSpec.constructorBuilder()
            .addParameter(WRAPPED_PROPERTY_NAME, originalTypeName)
            .callThisConstructor(
                buildCodeBlock {
                    add("%N", WRAPPED_PROPERTY_NAME)
                    add(",")
                    when (scopeProviderMemberName) {
                        null -> add("null")
                        else -> add("%M", scopeProviderMemberName)
                    }
                }
            )
            .build()

    private val wrappedClassPropertySpec =
        PropertySpec.builder(WRAPPED_PROPERTY_NAME, originalTypeName)
            .initializer(WRAPPED_PROPERTY_NAME)
            .addModifiers(KModifier.PRIVATE)
            .build()

    private val scopeProviderPropertySpec =
        PropertySpec.builder(SCOPE_PROVIDER_PROPERTY_NAME, ScopeProvider::class.asTypeName()
            .copy(nullable = true))
            .initializer(SCOPE_PROVIDER_PROPERTY_NAME)
            .addModifiers(KModifier.PRIVATE)
            .build()


    private val functions = originalTypeSpec.funSpecs
        .filter { !it.modifiers.contains(KModifier.PRIVATE) }
        .map { originalFuncSpec ->
            originalFuncSpec.toBuilder(name = originalFuncSpec.name)
                .clearBody()
                .setFunctionBody(originalFuncSpec)
                .setReturnType(originalFuncSpec)
                .apply {
                    modifiers.remove(KModifier.SUSPEND)
                    modifiers.remove(KModifier.ABSTRACT) //when we create class, we always wrap into a concrete impl
                    if (originalFuncSpec.overridesGeneratedInterface()) {
                        this.modifiers.add(KModifier.OVERRIDE)
                    }
                }
                .build()
        }

    private val properties = originalTypeSpec.propertySpecs
        .filter { !it.modifiers.contains(KModifier.PRIVATE) }
        .map { originalPropertySpec ->
            PropertySpec
                .builder(
                    name = originalPropertySpec.name,
                    type = originalPropertySpec.wrappedType
                )
                .getter(
                    FunSpec.getterBuilder()
                        .setGetterBody(originalPropertySpec)
                        .build()
                )
                .mutable(false)
                .apply {
                    modifiers.remove(KModifier.ABSTRACT)
                    if (originalPropertySpec.overridesGeneratedInterface()) {
                        this.modifiers.add(KModifier.OVERRIDE)
                    }
                }
                .build()
        }

    /**
     * if we have an interface generated based on class signature, we need to add the override
     * modifier to its methods explicitly
     */
    private fun FunSpec.overridesGeneratedInterface(): Boolean {

        //not comparing types because we're comparing koru-wrapped interface with original
        fun FunSpec.hasSameSignature(other: FunSpec) =
            this.name == other.name && this.parameters == other.parameters

        fun TypeSpec.containsFunctionSignature() =
            this.funSpecs.any { it.hasSameSignature(this@overridesGeneratedInterface) }

        return originalToGeneratedInterface?.generated?.typeSpec?.containsFunctionSignature() == true
    }

    /**
     * if we have an interface generated based on class signature, we need to add the override
     * modifier to its properties explicitly.
     */
    private fun PropertySpec.overridesGeneratedInterface(): Boolean {

        //not comparing types because we're comparing koru-wrapped interface with original
        fun PropertySpec.hasSameSignature(other: PropertySpec) = this.name == other.name

        fun TypeSpec.containsPropertySignature() =
            this.propertySpecs.any { it.hasSameSignature(this@overridesGeneratedInterface) }

        return originalToGeneratedInterface?.generated?.typeSpec?.containsPropertySignature() == true
    }

    /**
     * 1. Add all original superinterfaces.
     * 2. (optionally) Replace original superinterface if it's a standalone interface generated by @ToNativeInterface
     * 3. (optionally) Add the superinterface autogenerated from current class with @ToNativeInterface
     */
    private val superInterfaces: MutableList<TypeName> = originalTypeSpec.superinterfaces.keys
        .map { interfaceName ->
            when (originalToGeneratedInterface?.originalName == interfaceName) {
                false -> interfaceName
                true -> originalToGeneratedInterface!!.generated.name
            }
        }
        .toMutableList()
        .apply {
            if (originalTypeName == originalToGeneratedInterface?.originalName) {
                add(originalToGeneratedInterface.generated.name)
            }
        }

    //this could be simplified in the future, but for now: https://github.com/square/kotlinpoet/issues/966
    private fun FunSpec.Builder.setFunctionBody(originalFunSpec: FunSpec): FunSpec.Builder = when {
        originalFunSpec.isSuspend -> wrapOriginalSuspendFunction(originalFunSpec)
        originalFunSpec.returnType.isFlow -> wrapOriginalFlowFunction(originalFunSpec)
        else -> callOriginalBlockingFunction(originalFunSpec)
    }

    private fun FunSpec.Builder.setGetterBody(originalPropSpec: PropertySpec): FunSpec.Builder {
        val getterInvocation = when {
            originalPropSpec.type.isFlow -> flowWrapperFunctionBody(originalPropSpec.asInvocation()).toString()
            else -> "return ${originalPropSpec.asInvocation()}"
        }
        return this.addStatement(getterInvocation)
    }

    /** E.g. return SuspendWrapper(mainScopeProvider) { doSth(whatever) }*/
    private fun FunSpec.Builder.wrapOriginalSuspendFunction(
        originalFunSpec: FunSpec
    ): FunSpec.Builder = addCode(
        buildCodeBlock {
            add("return %T(", SuspendWrapper::class)
            add(SCOPE_PROVIDER_PROPERTY_NAME)
            add(") { ${originalFunSpec.asInvocation()} }")
        }
    )

    /** E.g. return FlowWrapper(mainScopeProvider, doSth(whatever)) */
    private fun FunSpec.Builder.wrapOriginalFlowFunction(
        originalFunSpec: FunSpec
    ): FunSpec.Builder = addCode(
        flowWrapperFunctionBody(originalFunSpec.asInvocation())
    )

    private fun flowWrapperFunctionBody(callOriginal: String) = buildCodeBlock {
        add("return %T(", FlowWrapper::class)
        add(SCOPE_PROVIDER_PROPERTY_NAME)
        add(", ${callOriginal})")
    }

    private fun FunSpec.Builder.callOriginalBlockingFunction(originalFunSpec: FunSpec): FunSpec.Builder =
        this.addStatement("return ${originalFunSpec.asInvocation()}")

    private fun FunSpec.asInvocation(): String {
        val paramsDeclaration = parameters.joinToString(", ") { it.name }
        return "${WRAPPED_PROPERTY_NAME}.${this.name}($paramsDeclaration)"
    }

    private fun PropertySpec.asInvocation(): String {
        return "${WRAPPED_PROPERTY_NAME}.${this.name}"
    }

    fun build(): TypeSpec = TypeSpec
        .classBuilder(newTypeName)
        .addSuperinterfaces(superInterfaces)
        .primaryConstructor(constructorSpec)
        .addFunction(secondaryConstructorSpec)
        .addProperty(wrappedClassPropertySpec)
        .addProperty(scopeProviderPropertySpec)
        .addProperties(properties)
        .addFunctions(functions)
        .build()

}
