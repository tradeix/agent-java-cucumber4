/*
 * Copyright 2020 EPAM Systems
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
package com.epam.reportportal.cucumber

import com.epam.reportportal.cucumber.RpItems.RP_STEP_TYPE
import com.epam.reportportal.cucumber.util.ItemTreeUtils
import com.epam.reportportal.listeners.ListenerParameters
import com.epam.reportportal.message.ReportPortalMessage
import com.epam.reportportal.service.Launch
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.service.tree.TestItemTree
import com.epam.reportportal.service.tree.TestItemTree.TestItemLeaf
import com.epam.reportportal.utils.properties.SystemAttributesExtractor
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ
import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ
import io.cucumber.plugin.ConcurrentEventListener
import io.cucumber.plugin.event.*
import io.reactivex.Maybe
import org.apache.tika.Tika
import org.apache.tika.mime.MimeTypeException
import org.apache.tika.mime.MimeTypes
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rp.com.google.common.base.Strings
import rp.com.google.common.base.Supplier
import rp.com.google.common.base.Suppliers
import rp.com.google.common.base.Throwables
import rp.com.google.common.io.ByteSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract Cucumber 4.x formatter for Report Portal
 *
 * @author Sergey Gvozdyukevich
 * @author Andrei Varabyeu
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 * @author Vadzim Hushchanskou
 */
abstract class AbstractReporter : ConcurrentEventListener {
    lateinit var launch: Supplier<Launch>
    private val currentFeatureContextMap: MutableMap<URI, FeatureContext> = ConcurrentHashMap()
    private val currentScenarioContextMap: MutableMap<Pair<Int, URI>, ScenarioContext> = ConcurrentHashMap()
    private val currentScenarioContext: ThreadLocal<ScenarioContext> = ThreadLocal()

    // There is no event for recognizing end of feature in Cucumber.
    // This map is used to record the last scenario time and its feature uri.
    // End of feature occurs once launch is finished.
    private val featureEndTime: MutableMap<URI, Date> = ConcurrentHashMap()

    /**
     * Registers an event handler for a specific event.
     *
     *
     * The available events types are:
     *
     *  * [TestRunStarted] - the first event sent.
     *  * [TestSourceRead] - sent for each feature file read, contains the feature file source.
     *  * [TestCaseStarted] - sent before starting the execution of a Test Case(/Pickle/Scenario), contains the Test Case
     *  * [TestStepStarted] - sent before starting the execution of a Test Step, contains the Test Step
     *  * [TestStepFinished] - sent after the execution of a Test Step, contains the Test Step and its Result.
     *  * [TestCaseFinished] - sent after the execution of a Test Case(/Pickle/Scenario), contains the Test Case and its Result.
     *  * [TestRunFinished] - the last event sent.
     *  * [EmbedEvent] - calling scenario.embed in a hook triggers this event.
     *  * [WriteEvent] - calling scenario.write in a hook triggers this event.
     *
     */
    override fun setEventPublisher(publisher: EventPublisher) {
        publisher.registerHandlerFor(TestRunStarted::class.java, testRunStartedHandler)
        publisher.registerHandlerFor(TestSourceRead::class.java, testSourceReadHandler)
        publisher.registerHandlerFor(TestCaseStarted::class.java, testCaseStartedHandler)
        publisher.registerHandlerFor(TestStepStarted::class.java, testStepStartedHandler)
        publisher.registerHandlerFor(TestStepFinished::class.java, testStepFinishedHandler)
        publisher.registerHandlerFor(TestCaseFinished::class.java, testCaseFinishedHandler)
        publisher.registerHandlerFor(TestRunFinished::class.java, testRunFinishedHandler)
        publisher.registerHandlerFor(EmbedEvent::class.java, embedEventHandler)
        publisher.registerHandlerFor(WriteEvent::class.java, writeEventHandler)
    }

    protected fun getCurrentScenarioContext(): ScenarioContext = currentScenarioContext.get()

    /**
     * Manipulations before the launch starts
     */
    open fun beforeLaunch() {
        launch = startLaunch()
        ITEM_TREE.launchId = launch.get().start()
    }

    /**
     * Extension point to customize ReportPortal instance
     *
     * @return ReportPortal
     */
    protected open fun buildReportPortal(): ReportPortal {
        return ReportPortal.builder().build()
    }

    /**
     * Finish RP launch
     */
    protected open fun afterLaunch() {
        launch.get().finish(FinishExecutionRQ().apply {
            endTime = Calendar.getInstance().time
        })
    }

    private fun addToTree(featureContext: FeatureContext, scenarioContext: ScenarioContext) {
        ItemTreeUtils.retrieveLeaf(featureContext.uri, ITEM_TREE).ifPresent { suiteLeaf: TestItemLeaf ->
            suiteLeaf.childItems[ItemTreeUtils.createKey(scenarioContext.line)] = TestItemTree.createTestItemLeaf(scenarioContext.id, DEFAULT_CAPACITY)
        }
    }

    /**
     * Start Cucumber scenario
     */
    protected fun beforeScenario(featureContext: FeatureContext, scenarioContext: ScenarioContext, scenarioName: String) {
        val codeRef: String = Utils.getCodeRef(featureContext.uri, scenarioContext.line)
        val myLaunch: Launch = launch.get()
        val id: Maybe<String> = Utils.startNonLeafNode(myLaunch,
                featureContext.featureId,
                scenarioName,
                featureContext.uri.toString(),
                codeRef,
                scenarioContext.attributes,
                scenarioTestItemType
        )
        scenarioContext.id = id
        if (myLaunch.parameters.isCallbackReportingEnabled) {
            addToTree(featureContext, scenarioContext)
        }
    }

    private fun removeFromTree(featureContext: FeatureContext, scenarioContext: ScenarioContext?) {
        ItemTreeUtils.retrieveLeaf(featureContext.uri, ITEM_TREE).ifPresent { suiteLeaf: TestItemLeaf ->
            suiteLeaf.childItems.remove(ItemTreeUtils.createKey(scenarioContext!!.line))
        }
    }

    /**
     * Finish Cucumber scenario
     * Put scenario end time in a map to check last scenario end time per feature
     */
    protected fun afterScenario(event: TestCaseFinished) {
        val context = getCurrentScenarioContext()
        val featureUri = context.featureUri
        currentScenarioContextMap.remove(context.line to featureUri)
        val endTime = Utils.finishTestItem(launch.get(), context.id!!, event.result.status)
        featureEndTime[featureUri] = endTime
        currentScenarioContext.set(null)
        removeFromTree((currentFeatureContextMap.get(context.featureUri))!!, context)
    }

    /**
     * Start RP launch
     */
    protected fun startLaunch(): Supplier<Launch> {
        return Suppliers.memoize {
            val reportPortal: ReportPortal = buildReportPortal()
            val parameters: ListenerParameters = reportPortal.parameters
            val rq = StartLaunchRQ().apply {
                name = parameters.launchName
                startTime = Calendar.getInstance().time
                mode = parameters.launchRunningMode
                attributes = parameters.attributes
                attributes.addAll(SystemAttributesExtractor.extract(AGENT_PROPERTIES_FILE, AbstractReporter::class.java.classLoader))
                description = parameters.description
                isRerun = parameters.isRerun
                if (!Strings.isNullOrEmpty(parameters.rerunOf)) {
                    rerunOf = parameters.rerunOf
                }
                parameters.skippedAnIssue?.let {
                    attributes.add(ItemAttributesRQ().apply {
                        key = SKIPPED_ISSUE_KEY
                        value = parameters.skippedAnIssue.toString()
                        isSystem = true
                    })
                }
            }
            reportPortal.newLaunch(rq)
        }
    }

    /**
     * Extension point to customize test creation event/request
     *
     * @param testStep a cucumber step object
     * @return Request to ReportPortal
     */
    protected open fun buildStartStepRequest(testStep: TestStep, stepPrefix: String, keyword: String): StartTestItemRQ {
        val method = Utils.getFieldValue<Any>(Utils.DEFINITION_MATCH_FIELD_NAME, testStep)?.let {
            Utils.retrieveMethod(it)
        }

        return StartTestItemRQ().apply {
            name = Utils.buildName(stepPrefix, keyword, Utils.getStepName(testStep))
            description = Utils.buildMultilineArgument(testStep)
            startTime = Calendar.getInstance().time
            type = RP_STEP_TYPE
            val codeRef: String? = Utils.getCodeRef(testStep)
            if (testStep is PickleStepTestStep) {
                testStep.definitionArgument.let { arguments ->
                    parameters = Utils.getParameters(codeRef, arguments)
                    testCaseId = (method?.let {
                        Utils.getTestCaseId(method, codeRef, arguments)
                    } ?: Utils.getTestCaseId(codeRef, arguments))?.id
                }
                this.codeRef = codeRef
                attributes = method?.let { Utils.getAttributes(it) }
            }
        }
    }

    /**
     * Start Cucumber step
     *
     * @param testStep a cucumber step object
     */
    protected fun beforeStep(testStep: TestStep) {
        val context = getCurrentScenarioContext()
        val step = context.getStep(testStep)
        val rq = buildStartStepRequest(testStep, context.stepPrefix, step.keyword)
        val myLaunch: Launch = launch.get()
        val stepId = myLaunch.startTestItem(context.id, rq)

        context.currentStepId = stepId
        context.currentText = step.text

        if (myLaunch.parameters.isCallbackReportingEnabled) {
            addToTree(context, step.text, stepId)
        }
    }

    /**
     * Finish Cucumber step
     *
     * @param result Step result
     */
    protected fun afterStep(result: Result) {
        reportResult(result, null)
        val context: ScenarioContext = getCurrentScenarioContext()
        val myLaunch: Launch = launch.get()
        myLaunch.stepReporter.finishPreviousStep()
        Utils.finishTestItem(myLaunch, context.currentStepId!!, result.status)
        context.currentStepId = null
    }

    /**
     * Extension point to customize test creation event/request
     *
     * @param hookType a cucumber hook type object
     * @return Request to ReportPortal
     */
    protected open fun buildStartHookRequest(hookType: HookType): StartTestItemRQ {
        val typeName = Utils.getHookTypeAndName(hookType)
        return StartTestItemRQ().apply {
            type = typeName.first
            name = typeName.second
            startTime = Calendar.getInstance().time
        }
    }

    /**
     * Called when before/after-hooks are started
     *
     * @param hookType a hook type
     */
    protected fun beforeHooks(hookType: HookType) {
        val rq: StartTestItemRQ = buildStartHookRequest(hookType)
        val context: ScenarioContext? = getCurrentScenarioContext()
        context!!.hookStepId = launch.get().startTestItem(context.id, rq)
        context.hookStatus = Status.PASSED
    }

    /**
     * Called when before/after-hooks are finisheda
     *
     * @param hookType a hook type
     */
    protected fun afterHooks(hookType: HookType) {
        val context = getCurrentScenarioContext()
        val myLaunch: Launch = launch.get()
        myLaunch.stepReporter.finishPreviousStep()
        Utils.finishTestItem(myLaunch, context.hookStepId!!, context.hookStatus)
        context.hookStepId = null
        if (hookType === HookType.AFTER_STEP) {
            removeFromTree(context, context.currentText)
        }
    }

    /**
     * Called when a specific before/after-hook is finished
     *
     * @param step     TestStep object
     * @param result   Hook result
     * @param isBefore - if true, before-hook, if false - after-hook
     */
    protected fun hookFinished(step: HookTestStep, result: Result, isBefore: Boolean) {
        reportResult(result, (if (isBefore) "Before" else "After") + " hook: " + step.codeLocation)
        getCurrentScenarioContext().hookStatus = result.status
    }

    /**
     * Return RP launch test item name mapped to Cucumber feature
     *
     * @return test item name
     */
    protected abstract val featureTestItemType: String

    /**
     * Return RP launch test item name mapped to Cucumber scenario
     *
     * @return test item name
     */

    protected abstract val scenarioTestItemType: String

    /**
     * Report test item result and error (if present)
     *
     * @param result  - Cucumber result object
     * @param message - optional message to be logged in addition
     */
    protected fun reportResult(result: Result, message: String?) {
        val level: String = Utils.mapLevel(result.status.toString())

        message?.let {
            Utils.sendLog(message, level)
        }
        result.error?.message?.let {
            Utils.sendLog(it, level)
        }

        result.error?.let {
            Utils.sendLog(Throwables.getStackTraceAsString(result.error), level)
        }
    }

    @kotlin.jvm.Volatile
    private var mimeTypes: MimeTypes? = null
        get() {
            if (field == null) {
                field = MimeTypes.getDefaultMimeTypes()
            }
            return field
        }

    /**
     * Send a log with data attached.
     *
     * @param mimeType an attachment type
     * @param data     data to attach
     */
    protected fun embedding(mimeType: String?, data: ByteArray) {
        val type = try {
            TIKA_THREAD_LOCAL.get().detect(ByteArrayInputStream(data))
        } catch (e: IOException) {
            // nothing to do we will use bypassed mime type
            LOGGER.warn("Mime-type not found", e)
            mimeType
        }

        val prefix = try {
            mimeTypes!!.forName(type).type.type
        } catch (e: MimeTypeException) {
            LOGGER.warn("Mime-type not found", e)
            ""
        }
        ReportPortal.emitLog(ReportPortalMessage(ByteSource.wrap(data), type, prefix), "UNKNOWN", Calendar.getInstance().time)
    }

    protected fun write(text: String?) {
        Utils.sendLog(text, "INFO")
    }

    protected abstract val rootItemId: Optional<Maybe<String>>

    private fun startFeatureContext(context: FeatureContext): FeatureContext {

        val rq = StartTestItemRQ().apply {
            description = context.uri.toString()
            codeRef = Utils.getCodeRef(context.uri, 0)
            name = Utils.buildName(context.currentFeature.keyword, COLON_INFIX, context.currentFeature.name)
            attributes = context.attributes
            startTime = Calendar.getInstance().time
            type = featureTestItemType
        }
        val root: Optional<Maybe<String>> = rootItemId
        context.featureId = root.map { r: Maybe<String>? -> launch.get().startTestItem(r, rq) }.orElseGet { launch.get().startTestItem(rq) }
        return context
    }

    /**
     * Private part that responsible for handling events
     */
    protected val testRunStartedHandler: EventHandler<TestRunStarted> = EventHandler<TestRunStarted> { beforeLaunch() }
    protected val testSourceReadHandler: EventHandler<TestSourceRead> = EventHandler<TestSourceRead> { event -> FeatureContext.addTestSourceReadEvent(event.uri, event) }
    protected val testCaseStartedHandler: EventHandler<TestCaseStarted> = EventHandler<TestCaseStarted> { event: TestCaseStarted -> handleStartOfTestCase(event) }
    protected val testStepStartedHandler: EventHandler<TestStepStarted> = EventHandler<TestStepStarted> { event: TestStepStarted -> handleTestStepStarted(event) }
    protected val testStepFinishedHandler: EventHandler<TestStepFinished> = EventHandler<TestStepFinished> { event: TestStepFinished -> handleTestStepFinished(event) }
    protected val testCaseFinishedHandler: EventHandler<TestCaseFinished> = EventHandler<TestCaseFinished> { event: TestCaseFinished -> afterScenario(event) }
    protected val testRunFinishedHandler: EventHandler<TestRunFinished> = EventHandler<TestRunFinished> {
        handleEndOfFeature()
        afterLaunch()
    }
    protected val embedEventHandler: EventHandler<EmbedEvent>
        get() {
            return EventHandler<EmbedEvent> { event -> embedding(event.mediaType, event.data) }
        }
    protected val writeEventHandler: EventHandler<WriteEvent>
        get() {
            return EventHandler<WriteEvent> { event -> write(event.text) }
        }

    private fun removeFromTree(featureContext: FeatureContext) {
        ITEM_TREE.testItems.remove(ItemTreeUtils.createKey(featureContext.uri.toString()))
    }

    protected fun handleEndOfFeature() {
        currentFeatureContextMap.values.forEach { f: FeatureContext ->
            val featureCompletionDateTime = featureEndTime[f.uri]!!
            Utils.finishFeature(launch.get(), f.featureId, featureCompletionDateTime)
            removeFromTree(f)
        }
        currentFeatureContextMap.clear()
    }

    private fun addToTree(context: FeatureContext) {
        ITEM_TREE.testItems[ItemTreeUtils.createKey(context.uri.toString())] = TestItemTree.createTestItemLeaf(context.featureId, DEFAULT_CAPACITY)
    }

    protected fun handleStartOfTestCase(event: TestCaseStarted) {
        val testCase = event.testCase
        val newFeatureContext = FeatureContext(testCase)

        val featureContext = currentFeatureContextMap.computeIfAbsent(newFeatureContext.uri) {
            startFeatureContext(newFeatureContext).also {
                if (launch.get().parameters.isCallbackReportingEnabled) {
                    addToTree(it)
                }
            }
        }
        if (featureContext.uri != testCase.uri) {
            throw IllegalStateException("Scenario URI does not match Feature URI.")
        }
        val newScenarioContext = featureContext.getScenarioContext(testCase)
        val scenarioName: String = Utils.buildName(newScenarioContext.scenario.keyword, COLON_INFIX, newScenarioContext.scenario.name)
        val scenarioContext: ScenarioContext = currentScenarioContextMap.computeIfAbsent(newScenarioContext.line to featureContext.uri) {
            currentScenarioContext.set(newScenarioContext)
            newScenarioContext
        }
        beforeScenario(featureContext, scenarioContext, scenarioName)
    }

    protected fun handleTestStepStarted(event: TestStepStarted) {
        val testStep: TestStep = event.testStep
        if (testStep is HookTestStep) {
            beforeHooks(testStep.hookType)
        } else {
            if (getCurrentScenarioContext().withBackground()) {
                getCurrentScenarioContext().nextBackgroundStep()
            }
            beforeStep(testStep)
        }
    }

    protected fun handleTestStepFinished(event: TestStepFinished) {
        val testStep = event.testStep
        if (testStep is HookTestStep) {
            hookFinished(testStep, event.result, HookType.BEFORE == testStep.hookType)
            afterHooks(testStep.hookType)
        } else {
            afterStep(event.result)
        }
    }

    protected fun addToTree(scenarioContext: ScenarioContext, text: String, stepId: Maybe<String?>?) {
        ItemTreeUtils.retrieveLeaf(scenarioContext.featureUri,
                scenarioContext.line,
                ITEM_TREE
        ).ifPresent { scenarioLeaf: TestItemLeaf -> scenarioLeaf.childItems[ItemTreeUtils.createKey(text)] = TestItemTree.createTestItemLeaf(stepId, 0) }
    }

    protected fun removeFromTree(scenarioContext: ScenarioContext, text: String) {
        ItemTreeUtils.retrieveLeaf(scenarioContext.featureUri, scenarioContext.line, ITEM_TREE)
                .ifPresent { scenarioLeaf: TestItemLeaf -> scenarioLeaf.childItems.remove(ItemTreeUtils.createKey(text)) }
    }

//    protected open fun setReportPortal(reportPortal: ReportPortal) {
//        AbstractReporter.reportPortal = reportPortal
//    }


    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(AbstractReporter::class.java)
        private const val AGENT_PROPERTIES_FILE: String = "agent.properties"
        private const val DEFAULT_CAPACITY: Int = 16
        val ITEM_TREE: TestItemTree = TestItemTree()

        @kotlin.jvm.Volatile
        var reportPortal: ReportPortal = ReportPortal.builder().build()
        const val COLON_INFIX: String = ": "
        private const val SKIPPED_ISSUE_KEY: String = "skippedIssue"
        private val TIKA_THREAD_LOCAL: ThreadLocal<Tika> = ThreadLocal.withInitial { Tika() }
    }
}