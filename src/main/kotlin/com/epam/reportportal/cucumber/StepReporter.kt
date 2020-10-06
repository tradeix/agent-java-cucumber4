package com.epam.reportportal.cucumber

import io.reactivex.Maybe
import java.util.*

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
/**
 * Cucumber reporter for ReportPortal that reports individual steps as test
 * methods.
 *
 *
 * Mapping between Cucumber and ReportPortal is as follows:
 *
 *  * feature - SUITE
 *  * scenario - TEST
 *  * step - STEP
 *
 * Background steps are reported as part of corresponding scenarios. Outline
 * example rows are reported as individual scenarios with [ROW NUMBER] after the
 * name. Hooks are reported as BEFORE/AFTER_METHOD items (NOTE: all screenshots
 * created in hooks will be attached to these, and not to the actual failing
 * steps!)
 *
 * @author Sergey_Gvozdyukevich
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 */
open class StepReporter : AbstractReporter() {

    override val rootItemId: Optional<Maybe<String>> = Optional.empty()

    override val featureTestItemType = RP_STORY_TYPE

    override val scenarioTestItemType = RP_TEST_TYPE

    companion object {
        private const val RP_STORY_TYPE = "STORY"
        private const val RP_TEST_TYPE = "SCENARIO"
    }
}