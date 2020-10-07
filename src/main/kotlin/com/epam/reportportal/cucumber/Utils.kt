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

import com.epam.reportportal.annotations.TestCaseId
import com.epam.reportportal.annotations.attribute.Attributes
import com.epam.reportportal.listeners.ItemStatus
import com.epam.reportportal.service.Launch
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.service.item.TestCaseIdEntry
import com.epam.reportportal.utils.AttributeParser
import com.epam.reportportal.utils.ParameterUtils
import com.epam.reportportal.utils.TestCaseIdUtils
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ
import com.epam.ta.reportportal.ws.model.ParameterResource
import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import io.cucumber.core.backend.StepDefinition
import io.cucumber.core.internal.gherkin.ast.Tag
import io.cucumber.core.internal.gherkin.pickles.PickleString
import io.cucumber.core.internal.gherkin.pickles.PickleTable
import io.cucumber.plugin.event.*
import io.reactivex.Maybe
import org.slf4j.LoggerFactory
import rp.com.google.common.collect.ImmutableMap
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URI
import java.util.*

object RpItems {
    const val RP_STEP_TYPE = "STEP"
}

object Utils {
    private val LOGGER = LoggerFactory.getLogger(Utils::class.java)
    private const val TABLE_INDENT = "          "
    private const val TABLE_SEPARATOR = "|"
    private const val DOCSTRING_DECORATOR = "\n\"\"\"\n"
    private const val PASSED = "passed"
    private const val SKIPPED = "skipped"
    private const val INFO = "INFO"
    private const val WARN = "WARN"
    private const val ERROR = "ERROR"
    private const val EMPTY = ""
    private const val ONE_SPACE = " "
    private const val HOOK_ = "Hook: "
    private const val NEW_LINE = "\r\n"
    private const val FILE_PREFIX = "file:"
    const val DEFINITION_MATCH_FIELD_NAME = "definitionMatch"
    private const val STEP_DEFINITION_FIELD_NAME = "stepDefinition"
    private const val GET_LOCATION_METHOD_NAME = "getLocation"
    private const val METHOD_OPENING_BRACKET = "("
    private const val METHOD_FIELD_NAME = "method"

    //@formatter:off
    private val STATUS_MAPPING: Map<Status, ItemStatus?> = ImmutableMap.builder<Status, ItemStatus>()
            .put(Status.PASSED, ItemStatus.PASSED)
            .put(Status.FAILED, ItemStatus.FAILED)
            .put(Status.SKIPPED, ItemStatus.SKIPPED)
            .put(Status.PENDING, ItemStatus.SKIPPED)
            .put(Status.AMBIGUOUS, ItemStatus.SKIPPED)
            .put(Status.UNDEFINED, ItemStatus.SKIPPED)
            .put(Status.UNUSED, ItemStatus.SKIPPED).build()

    //@formatter:on
    fun finishFeature(rp: Launch, itemId: Maybe<String>?, dateTime: Date?) {
        if (itemId == null) {
            LOGGER.error("BUG: Trying to finish unspecified test item.")
            return
        }
        val rq = FinishTestItemRQ()
        rq.endTime = dateTime
        rp.finishTestItem(itemId, rq)
    }

    fun finishTestItem(rp: Launch, itemId: Maybe<String>) {
        finishTestItem(rp, itemId, null)
    }

    fun finishTestItem(rp: Launch, itemId: Maybe<String>, status: Status?): Date {
        val rq = FinishTestItemRQ()
        val endTime = Calendar.getInstance().time
        rq.endTime = endTime
        rq.status = mapItemStatus(status)
        rp.finishTestItem(itemId, rq)
        return endTime
    }

    fun startNonLeafNode(rp: Launch, rootItemId: Maybe<String>, name: String, description: String, codeRef: String?,
                         attributes: Set<ItemAttributesRQ?>?, type: String): Maybe<String> {
        val rq = StartTestItemRQ().apply {
            this.description = description
            this.codeRef = codeRef
            this.name = name
            this.attributes = attributes
            startTime = Calendar.getInstance().time
            this.type = type
            if (type == RpItems.RP_STEP_TYPE) {
                testCaseId = getTestCaseId(codeRef, null)?.id
            }
        }
        return rp.startTestItem(rootItemId, rq)
    }

    fun sendLog(message: String?, level: String?) {
        ReportPortal.emitLog(message, level, Calendar.getInstance().time)
    }

    /**
     * Transform tags from Cucumber to RP format
     *
     * @param tags - Cucumber tags
     * @return set of tags
     */
    fun extractTags(tags: List<String>): Set<ItemAttributesRQ> {
        return tags.map { ItemAttributesRQ(null, it) }.toSet()
    }

    /**
     * Transform tags from Cucumber to RP format
     *
     * @param tags - Cucumber tags
     * @return set of tags
     */
    fun extractAttributes(tags: List<Tag>): Set<ItemAttributesRQ> {
        return tags.map { ItemAttributesRQ(null, it.name) }.toSet()
    }

    /**
     * Map Cucumber statuses to RP log levels
     *
     * @param cukesStatus - Cucumber status
     * @return regular log level
     */
    fun mapLevel(cukesStatus: String): String {
        return when (cukesStatus.toUpperCase()) {
            PASSED -> INFO
            SKIPPED -> WARN
            else -> ERROR
        }
    }

    /**
     * Map Cucumber statuses to RP item statuses
     *
     * @param status - Cucumber status
     * @return RP test item status and null if status is null
     */
    fun mapItemStatus(status: Status?): String? {
        return status?.let {
            STATUS_MAPPING[status]?.name ?: ItemStatus.SKIPPED.name.also {
                LOGGER.error("Unable to find direct mapping between Cucumber and ReportPortal for TestItem with status: $status")
            }
        }
    }

    /**
     * Generate name representation
     *
     * @param prefix   - substring to be prepended at the beginning (optional)
     * @param infix    - substring to be inserted between keyword and name
     * @param argument - main text to process
     * @return transformed string
     */
    fun buildName(prefix: String?, infix: String, argument: String): String {
        return (prefix ?: EMPTY) + infix + argument
    }

    /**
     * Generate multiline argument (DataTable or DocString) representation
     *
     * @param step - Cucumber step object
     * @return - transformed multiline argument (or empty string if there is
     * none)
     */
    fun buildMultilineArgument(step: TestStep): String {
        return (step as PickleStepTestStep?)?.definitionArgument?.firstOrNull()?.let { argument ->
            when (argument) {
                is PickleString -> StringBuilder().append(DOCSTRING_DECORATOR).append(argument.content).append(DOCSTRING_DECORATOR).toString()

                is PickleTable -> {
                    StringBuilder().append(NEW_LINE).let {
                        for (row in argument.rows) {
                            it.append(TABLE_INDENT).append(TABLE_SEPARATOR)
                            for (cell in row.cells) {
                                it.append(ONE_SPACE).append(cell.value).append(ONE_SPACE).append(TABLE_SEPARATOR)
                            }
                            it.append(NEW_LINE)
                        }
                    }.toString()
                }
                else -> ""
            }
        } ?: ""
    }

    fun getStepName(step: TestStep): String {
        return if (step is HookTestStep) HOOK_ + step.hookType.toString() else (step as PickleStepTestStep).step.text
    }

    fun getAttributes(method: Method): Set<ItemAttributesRQ>? {
        return method.getAnnotation(Attributes::class.java)?.let { attributes ->
            return AttributeParser.retrieveAttributes(attributes)
        }
    }

    fun getCodeRef(testStep: TestStep): String? {
        return getFieldValue<Any>(DEFINITION_MATCH_FIELD_NAME, testStep)?.let { definitionMatch ->
            getFieldValue<StepDefinition>(STEP_DEFINITION_FIELD_NAME, definitionMatch)
        }?.let { stepDefinition ->
            try {
                getMethod(stepDefinition, GET_LOCATION_METHOD_NAME)?.let {
                    it.invoke(stepDefinition)?.toString()?.let { location ->
                        location.substring(0, location.indexOf(METHOD_OPENING_BRACKET))
                    }
                }
            } catch (e: NoSuchFieldException) {
                null
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: InvocationTargetException) {
                null
            } catch (e: IllegalAccessException) {
                null
            }
        }
    }


    fun getCodeRef(uri: URI, line: Int): String {
        return "${uri.path.let { it.substring(it.indexOf("src")) }}:$line"
//        return uri.toString()
//        val myUri = if (uri.startsWith(FILE_PREFIX)) uri.substring(FILE_PREFIX.length) else uri
//        return "$myUri:$line"
    }

    fun getParameters(codeRef: String?, arguments: List<Argument>): List<ParameterResource> {
        val params = arguments.mapIndexed { i, argument -> org.apache.commons.lang3.tuple.Pair.of("arg$i", argument.value) }
        return ParameterUtils.getParameters(codeRef, params)
    }

    fun retrieveMethod(definitionMatchField: Any): Method? {
        return try {
            getFieldValue<Any>(STEP_DEFINITION_FIELD_NAME, definitionMatchField)?.let { stepDefinition ->
                getFieldValue<Method>(METHOD_FIELD_NAME, stepDefinition)
            }
        } catch (ignore: NoSuchFieldException) {
            null
        } catch (ignore: IllegalAccessException) {
            null
        }
    }

    fun getTestCaseId(method: Method, codeRef: String?, arguments: List<Argument>): TestCaseIdEntry? {
        return try {
            TestCaseIdUtils.getTestCaseId(method.getAnnotation(TestCaseId::class.java),
                    method,
                    codeRef,
                    arguments.map { it.value }
            )
        } catch (ignore: NoSuchFieldException) {
            null
        } catch (ignore: IllegalAccessException) {
            null
        }
    }

    fun getTestCaseId(codeRef: String?, arguments: List<Argument>?): TestCaseIdEntry? {
        return TestCaseIdUtils.getTestCaseId(codeRef, arguments?.map { it.value })
    }

    inline fun <reified T> getFieldValue(fieldName: String, obj: Any): T? {
        var clazz: Class<*>? = obj::class.java
        var field: Field? = null
        return try {
            clazz!!.getField(fieldName).apply {
                isAccessible = true
            }
        } catch (e: NoSuchFieldException) {
            do {
                try {
                    field = clazz!!.getDeclaredField(fieldName).apply {
                        isAccessible = true
                    }
                    break
                } catch (ignore: NoSuchFieldException) {
                }
                clazz = clazz!!.superclass
            } while (clazz != null)
            field
        }?.let {
            it[obj] as? T
        }
    }

    fun getMethod(obj: Any, methodName: String, vararg methodTypes: Class<*>): Method? {
        var clazz: Class<*>? = obj::class.java
        var method: Method? = null
        return try {
            clazz!!.getMethod(methodName, *methodTypes).apply {
                isAccessible = true
            }
        } catch (e: NoSuchFieldException) {
            do {
                try {
                    method = clazz!!.getDeclaredMethod(methodName, *methodTypes).apply {
                        isAccessible = true
                    }
                    break
                } catch (ignore: NoSuchFieldException) {
                }
                clazz = clazz!!.superclass
            } while (clazz != null)
            method
        }
    }

    private inline fun <reified T> getMethodInvokeValue(fieldName: String, obj: Any): T? {
        var clazz: Class<*>? = obj::class.java
        var field: Field? = null
        return try {
            clazz!!.getField(fieldName)
        } catch (e: NoSuchFieldException) {
            do {
                try {
                    field = clazz!!.getDeclaredField(fieldName).apply {
                        isAccessible = true
                    }
                    break
                } catch (ignore: NoSuchFieldException) {
                }
                clazz = clazz!!.superclass
            } while (clazz != null)
            field
        }?.let {
            it[obj] as? T
        }
    }

    fun getHookTypeAndName(hookType: HookType?): Pair<String, String> {
        return when (hookType) {
            HookType.BEFORE -> "Before hooks" to "BEFORE_TEST"
            HookType.AFTER -> "After hooks" to "AFTER_TEST"
            HookType.AFTER_STEP -> "After step" to "AFTER_METHOD"
            HookType.BEFORE_STEP -> "Before step" to "BEFORE_METHOD"
            else -> throw IllegalArgumentException("Not supported hook type: $hookType")
        }
    }
}

