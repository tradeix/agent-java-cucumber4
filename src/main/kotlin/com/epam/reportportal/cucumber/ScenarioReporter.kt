/*
 * Copyright 2018 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.cucumber

import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import io.cucumber.plugin.event.HookType
import io.cucumber.plugin.event.TestStep
import io.reactivex.Maybe
import rp.com.google.common.base.Supplier
import rp.com.google.common.base.Suppliers
import java.util.*

/**
 * Cucumber reporter for ReportPortal that reports scenarios as test methods.
 *
 *
 * Mapping between Cucumber and ReportPortal is as follows:
 *
 *  * feature - TEST
 *  * scenario - STEP
 *  * step - log item
 *
 *
 *
 * Dummy "Root Test Suite" is created because in current implementation of RP
 * test items cannot be immediate children of a launch
 *
 *
 * Background steps and hooks are reported as part of corresponding scenarios.
 * Outline example rows are reported as individual scenarios with [ROW NUMBER]
 * after the name.
 *
 * @author Sergey_Gvozdyukevich
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 */
open class ScenarioReporter : AbstractReporter() {
    companion object {
        private const val RP_STORY_TYPE = "SUITE"
        private const val RP_TEST_TYPE = "STORY"
        private const val RP_STEP_TYPE = "STEP"
        private const val DUMMY_ROOT_SUITE_NAME = "Root User Story"
    }

    protected var rootSuiteId: Supplier<Maybe<String>>? = null
    override fun beforeLaunch() {
        super.beforeLaunch()
        startRootItem()
    }

    override val featureTestItemType = RP_TEST_TYPE

    override val scenarioTestItemType = RP_STEP_TYPE

    override fun buildStartStepRequest(testStep: TestStep, stepPrefix: String, keyword: String): StartTestItemRQ {
        val rq = super.buildStartStepRequest(testStep, stepPrefix, keyword)
        rq.isHasStats = false
        return rq
    }

    override fun buildStartHookRequest(hookType: HookType): StartTestItemRQ {
        val rq = super.buildStartHookRequest(hookType)
        rq.isHasStats = false
        return rq
    }

    override val rootItemId: Optional<Maybe<String>>
        get() = Optional.of(rootSuiteId!!.get())

    override fun afterLaunch() {
        finishRootItem()
        super.afterLaunch()
    }

    /**
     * Finish root suite
     */
    protected fun finishRootItem() {
        Utils.finishTestItem(launch.get(), rootSuiteId!!.get())
        rootSuiteId = null
    }

    /**
     * Start root suite
     */
    protected fun startRootItem() {
        rootSuiteId = Suppliers.memoize {
            val rq = StartTestItemRQ()
            rq.name = DUMMY_ROOT_SUITE_NAME
            rq.startTime = Calendar.getInstance().time
            rq.type = RP_STORY_TYPE
            launch.get().startTestItem(rq)
        }
    }
}