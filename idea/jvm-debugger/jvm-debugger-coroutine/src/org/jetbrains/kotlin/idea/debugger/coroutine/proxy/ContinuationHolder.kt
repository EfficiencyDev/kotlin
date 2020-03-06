/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JVMStackFrameInfoProvider
import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.ui.ColoredTextContainer
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.*
import com.sun.jdi.request.EventRequest
import org.jetbrains.kotlin.idea.debugger.SUSPEND_LAMBDA_CLASSES
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data.ContinuationValueDescriptorImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data.DefaultCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.isSubtype
import org.jetbrains.kotlin.idea.debugger.logger
import org.jetbrains.kotlin.idea.debugger.safeVisibleVariableByName
import org.jetbrains.kotlin.idea.debugger.stackFrame.KotlinStackFrame

data class ContinuationHolder(val continuation: ObjectReference, val context: ExecutionContext, val threadProxy: ThreadReferenceProxyImpl) {
    val log by logger

    fun getAsyncStackTraceIfAny(): List<CoroutineStackFrameItem> {
        val frames = mutableListOf<CoroutineStackFrameItem>()
        try {
            collectFramesRecursively(frames)
        } catch (e: Exception) {
            log.error("Error while looking for variables.", e)
        }
        return frames
    }

    private fun collectFramesRecursively(consumer: MutableList<CoroutineStackFrameItem>) {
        var completion = this
        val debugMetadataKtType = debugMetadataKtType() ?: return
        while (completion.isBaseContinuationImpl()) {
            val location = createLocation(completion, debugMetadataKtType)

            location?.let {
                val spilledVariables = getSpilledVariables(completion, debugMetadataKtType) ?: emptyList()
                consumer.add(DefaultCoroutineStackFrameItem(location, spilledVariables))
            }
            completion = completion.findCompletion() ?: return
        }
    }

    private fun createLocation(continuation: ContinuationHolder, debugMetadataKtType: ClassType): GeneratedLocation? {
        val instance = invokeGetStackTraceElement(continuation, debugMetadataKtType) ?: return null
        val className = context.invokeMethodAsString(instance, "getClassName") ?: return null
        val methodName = context.invokeMethodAsString(instance, "getMethodName") ?: return null
        val lineNumber = context.invokeMethodAsInt(instance, "getLineNumber")?.takeIf {
            it >= 0
        } ?: return null
        val locationClass = context.findClassSafe(className) ?: return null
        return GeneratedLocation(context.debugProcess, locationClass, methodName, lineNumber)
    }

    private fun invokeGetStackTraceElement(continuation: ContinuationHolder, debugMetadataKtType: ClassType): ObjectReference? {

        val stackTraceElement =
            context.invokeMethodAsObject(debugMetadataKtType, "getStackTraceElement", continuation.value()) ?: return null

        stackTraceElement.referenceType().takeIf { it.name() == StackTraceElement::class.java.name } ?: return null

        context.keepReference(stackTraceElement)
        return stackTraceElement
    }

    fun getSpilledVariables(): List<XNamedValue>? {
        debugMetadataKtType()?.let {
            return getSpilledVariables(this, it)
        }
        return null
    }

    private fun getSpilledVariables(continuation: ContinuationHolder, debugMetadataKtType: ClassType): List<XNamedValue>? {
        val rawSpilledVariables =
            context.invokeMethodAsArray(
                debugMetadataKtType,
                "getSpilledVariableFieldMapping",
                "(Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;)[Ljava/lang/String;",
                continuation.value()
            ) ?: return null

        context.keepReference(rawSpilledVariables)

        val length = rawSpilledVariables.length() / 2
        val spilledVariables = ArrayList<XNamedValue>(length)

        for (index in 0 until length) {
            val (fieldName, variableName) = getFieldVariableName(rawSpilledVariables, index) ?: continue

            val valueDescriptor = ContinuationValueDescriptorImpl(context.project, continuation, fieldName, variableName)

            spilledVariables += JavaValue.create(
                null,
                valueDescriptor,
                context.evaluationContext,
                context.debugProcess.xdebugProcess!!.nodeManager,
                false
            )
        }

        return spilledVariables
    }

    private fun getFieldVariableName(rawSpilledVariables: ArrayReference, index: Int): FieldVariable? {
        val fieldName = (rawSpilledVariables.getValue(2 * index) as? StringReference)?.value() ?: return null
        val variableName = (rawSpilledVariables.getValue(2 * index + 1) as? StringReference)?.value() ?: return null
        return FieldVariable(fieldName, variableName)
    }

    private fun debugMetadataKtType(): ClassType? {
        val debugMetadataKtType = context.findClassSafe(DEBUG_METADATA_KT)
        if (debugMetadataKtType != null) {
            return debugMetadataKtType
        } else {
            log.warn("Continuation information found but no $DEBUG_METADATA_KT class exists. Please check kotlin-stdlib version.")
        }
        return null
    }

    fun referenceType(): ClassType? =
        continuation.referenceType() as? ClassType

    fun value() =
        continuation

    fun field(field: Field): Value? =
        continuation.getValue(field)

    fun findCompletion(): ContinuationHolder? {
        val type = continuation.type()
        if (type is ClassType && isBaseContinuationImpl(type)) {
            val completionField = type.fieldByName(COMPLETION_FIELD_NAME) ?: return null
            return ContinuationHolder(continuation.getValue(completionField) as? ObjectReference ?: return null, context, threadProxy)
        }
        return null
    }

    fun isBaseContinuationImpl() =
        isBaseContinuationImpl(continuation.type())

    private fun isBaseContinuationImpl(type: Type): Boolean {
        return type is ClassType && type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME)
    }

    companion object {
        const val DEBUG_METADATA_KT = "kotlin.coroutines.jvm.internal.DebugMetadataKt"
        const val BASE_CONTINUATION_IMPL_CLASS_NAME = "kotlin.coroutines.jvm.internal.BaseContinuationImpl"
        const val COMPLETION_FIELD_NAME = "completion"

        fun lookup(context: ExecutionContext, method: Method, threadProxy: ThreadReferenceProxyImpl): ContinuationHolder? {
//            if (isInvokeSuspendMethod(method)) {
//                val tmp = context.frameProxy.thisObject() ?: return null
//                if (!isSuspendLambda(tmp.referenceType()))
//                    return null
//                return ContinuationHolder(tmp, context, threadProxy)
//            } else if (isSuspendMethod(method)) {
//                var continuation = getVariableValue(context.frameProxy, CONTINUATION_VARIABLE_NAME) ?: return null
//                context.keepReference(continuation)
//                return ContinuationHolder(continuation, context, threadProxy)
//            } else {
            return null
//            }
        }

        fun lookupForResumeContinuation(
            context: ExecutionContext,
            method: Method,
            threadProxy: ThreadReferenceProxyImpl
        ): ContinuationHolder? {
            if (isResumeMethodFrame(method)) {
                var continuation = getVariableValue(context.frameProxy, COMPLETION_FIELD_NAME) ?: return null
                context.keepReference(continuation)
                return ContinuationHolder(continuation, context, threadProxy)
            } else
                return null
        }

        fun coroutineExitFrame(
            frame: StackFrameProxyImpl,
            debugProcess: DebugProcessImpl
        ): XStackFrame? {
            val suspendContext = debugProcess.debuggerContext.suspendContext ?: return null
            if (suspendContext.suspendPolicy == EventRequest.SUSPEND_ALL && lookupPreFlight(frame.location().method())) {
                var frames = frame.threadProxy().frames()
                val indexOfCurrentFrame = frames.indexOf(frame)
                if (frames.size > indexOfCurrentFrame) {
                    val nextFrame = frames[indexOfCurrentFrame + 1] ?: return null
                    if (isResumeMethodFrame(nextFrame.location().method())) {
                        val context = ExecutionContext(EvaluationContextImpl(suspendContext, nextFrame), nextFrame)
                        val ch = lookupForResumeContinuation(context, nextFrame.location().method(), frame.threadProxy()) ?: return null
                        val coroutineStackTrace = ch.getAsyncStackTraceIfAny()
                        return CoroutinePreflightFrame(frame, nextFrame, indexOfCurrentFrame, coroutineStackTrace)
                    }
                }
            }
            return null
        }

        fun lookupPreFlight(method: Method) =
            isInvokeSuspendMethod(method) && method.location().lineNumber() == -1

        fun isResumeMethodFrame(method: Method) =
            method.name() == "resumeWith"

        private fun getVariableValue(sfp: StackFrameProxyImpl, variableName: String): ObjectReference? {
            val continuationVariable = sfp.safeVisibleVariableByName(variableName) ?: return null
            return sfp.getValue(continuationVariable) as? ObjectReference ?: return null
        }

        private fun isInvokeSuspendMethod(method: Method): Boolean =
            method.name() == "invokeSuspend" && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;"

        /**
         * @TODO
         * Checks if the method has a Continuation as input parameter.
         */
        private fun isSuspendMethod(method: Method): Boolean {
            val argTypes = method.argumentTypes()
            for (arg in argTypes) {
                println(arg)
            }
            return "Lkotlin/coroutines/Continuation;)" in method.signature()
        }

        private fun isSuspendLambda(referenceType: ReferenceType): Boolean =
            SUSPEND_LAMBDA_CLASSES.any { referenceType.isSubtype(it) }

        /**
         * Find continuation for the [frame]
         * Gets current CoroutineInfo.lastObservedFrame and finds next frames in it until null or needed stackTraceElement is found
         * @return null if matching continuation is not found or is not BaseContinuationImpl
         */
        fun lookup(
            context: ExecutionContext,
            initialContinuation: ObjectReference?,
            frame: StackTraceElement,
            threadProxy: ThreadReferenceProxyImpl
        ): ContinuationHolder? {
            var continuation = initialContinuation ?: return null
            val classLine = ClassNameLineNumber(frame.className, frame.lineNumber)

            do {
                val position = getClassAndLineNumber(context, continuation)
                // while continuation is BaseContinuationImpl and it's frame equals to the current
                continuation = getNextFrame(context, continuation) ?: return null
            } while (isSubtypeOfBaseContinuationImpl(continuation.type()) && position != classLine)


            return if (isSubtypeOfBaseContinuationImpl(continuation.type()))
                ContinuationHolder(continuation, context, threadProxy)
            else
                return null
        }

        /**
         * Finds previous Continuation for this Continuation (completion field in BaseContinuationImpl)
         * @return null if given ObjectReference is not a BaseContinuationImpl instance or completion is null
         */
        private fun getNextFrame(context: ExecutionContext, continuation: ObjectReference): ObjectReference? {
            if (!isSubtypeOfBaseContinuationImpl(continuation.type()))
                return null
            val type = continuation.type() as ClassType
            val next = type.concreteMethodByName("getCompletion", "()Lkotlin/coroutines/Continuation;")
            return context.invokeMethod(continuation, next, emptyList()) as? ObjectReference
        }

        fun isSubtypeOfBaseContinuationImpl(type: Type) =
            type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME)

        data class ClassNameLineNumber(val className: String?, val lineNumber: Int?)

        private fun getClassAndLineNumber(context: ExecutionContext, continuation: ObjectReference): ClassNameLineNumber {
            val objectReference = findStackTraceElement(context, continuation) ?: return ClassNameLineNumber(null, null)
            val classStackTraceElement = context.findClass("java.lang.StackTraceElement") as ClassType
            val getClassName = classStackTraceElement.concreteMethodByName("getClassName", "()Ljava/lang/String;")
            val getLineNumber = classStackTraceElement.concreteMethodByName("getLineNumber", "()I")
            val className = (context.invokeMethod(objectReference, getClassName, emptyList()) as StringReference).value()
            val lineNumber = (context.invokeMethod(objectReference, getLineNumber, emptyList()) as IntegerValue).value()
            return ClassNameLineNumber(className, lineNumber)
        }

        private fun findStackTraceElement(context: ExecutionContext, continuation: ObjectReference): ObjectReference? {
            val classType = continuation.type() as ClassType
            val methodGetStackTraceElement = classType.concreteMethodByName("getStackTraceElement", "()Ljava/lang/StackTraceElement;")
            return context.invokeMethod(continuation, methodGetStackTraceElement, emptyList()) as? ObjectReference
        }
    }
}

data class FieldVariable(val fieldName: String, val variableName: String)


class CoroutinePreflightFrame(
    val invokeSuspendFrame: StackFrameProxyImpl,
    val resumeWithFrame: StackFrameProxyImpl,
    val preflightIndex: Int,
    val coroutineStackFrame: List<CoroutineStackFrameItem>
) : KotlinStackFrame(resumeWithFrame), JVMStackFrameInfoProvider {
    override fun customizePresentation(component: ColoredTextContainer) {
        super.customizePresentation(component)
    }

    override fun isInLibraryContent() =
        true

    override fun isSynthetic() =
        true

}