/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.StackFrameInterceptor
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ContinuationHolder

class CoroutineStackFrameInterceptor(val project: Project) : StackFrameInterceptor {
    override fun createStackFrame(frame: StackFrameProxyImpl, debugProcess: DebugProcessImpl, location: Location): XStackFrame? {
        return ContinuationHolder.coroutineExitFrame(frame, debugProcess)
    }
}