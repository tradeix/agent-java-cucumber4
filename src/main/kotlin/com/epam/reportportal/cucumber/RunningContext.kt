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

import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import io.cucumber.core.internal.gherkin.AstBuilder
import io.cucumber.core.internal.gherkin.Parser
import io.cucumber.core.internal.gherkin.TokenMatcher
import io.cucumber.core.internal.gherkin.ast.*
import io.cucumber.core.internal.gherkin.ast.Step
import io.cucumber.plugin.event.*
import io.reactivex.Maybe
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.collections.HashMap
import kotlin.collections.HashSet

/**
 * Running context that contains mostly manipulations with Gherkin objects.
 * Keeps necessary information regarding current Feature, Scenario and Step
 *
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 * @author Vadzim Hushchanskou
 */
class FeatureContext(testCase: TestCase) {
    val uri: URI
    val currentFeature: Feature
    val attributes: Set<ItemAttributesRQ>
    lateinit var featureId: Maybe<String>

    init {
        val event = PATH_TO_READ_EVENT_MAP.getValue(testCase.uri)
        currentFeature = getFeature(event.source)
        uri = event.uri
        attributes = Utils.extractAttributes(currentFeature.tags)
    }

    fun getScenarioContext(testCase: TestCase): ScenarioContext {
        val scenario = getScenario(testCase)
        return ScenarioContext().apply {
            processTags(testCase.tags)
            processScenario(scenario)
            this.testCase = testCase
            processBackground(background)
            processScenarioOutline(scenario)
            featureUri = uri
        }
    }

    fun getFeature(source: String): Feature {
        val parser = Parser(AstBuilder())
        val gherkinDocument = parser.parse(source, TokenMatcher())
        return gherkinDocument.feature
    }

    val background: Background?
        get() = currentFeature.children.first() as Background


    fun getScenario(testCase: TestCase): ScenarioDefinition {
        return currentFeature.children!!.first {
            (testCase.line == it.location.line && testCase.name == it.name) || it is ScenarioOutline && it.examples.map { it.tableBody }.flatten().find { it.location.line == testCase.line } != null
        }
    }


    companion object {
        private val PATH_TO_READ_EVENT_MAP: MutableMap<URI, TestSourceRead> = ConcurrentHashMap<URI, TestSourceRead>()
        fun addTestSourceReadEvent(path: URI, event: TestSourceRead) {
            PATH_TO_READ_EVENT_MAP[path] = event
        }
    }


}


class ScenarioContext {

    companion object {
        private val scenarioOutlineMap: MutableMap<ScenarioDefinition, List<Int>> = ConcurrentHashMap<ScenarioDefinition, List<Int>>()
    }

    private val backgroundSteps: Queue<Step> = ArrayDeque()
    private val scenarioLocationMap: MutableMap<Int, Step> = HashMap()
    var attributes: Set<ItemAttributesRQ> = HashSet()
        private set
    var currentStepId: Maybe<String>? = null
    var hookStepId: Maybe<String>? = null
    var hookStatus: Status? = null
    var id: Maybe<String>? = null
        set(newId) {
            check(id == null) { "Attempting re-set scenario ID for unfinished scenario: ${scenario.name}" }
            field = newId
        }
    private var background: Background? = null
    lateinit var scenario: ScenarioDefinition

    lateinit var testCase: TestCase
    private var hasBackground = false

    var outlineIteration: String? = null
        private set

    lateinit var featureUri: URI
    lateinit var currentText: String

    val line: Int = if (isScenarioOutline(scenario)) testCase.line else scenario.location.line
    val stepPrefix: String = if (hasBackground() && withBackground()) background?.keyword?.toUpperCase().toString() + AbstractReporter.COLON_INFIX else ""


    fun processScenario(scenario: ScenarioDefinition) {
        this.scenario = scenario
        scenarioLocationMap.putAll(scenario.steps.map { it.location.line to it })
    }

    fun processBackground(background: Background?) {
        if (background != null) {
            this.background = background
            hasBackground = true
            backgroundSteps.addAll(background.steps)
            mapBackgroundSteps(background)
        }
    }

    /**
     * Takes the serial number of scenario outline and links it to the executing scenario
     */
    fun processScenarioOutline(scenarioOutline: ScenarioDefinition) {
        if (isScenarioOutline(scenarioOutline)) {
            scenarioOutlineMap.computeIfAbsent(scenarioOutline
            ) { k: ScenarioDefinition? ->
                (scenarioOutline as ScenarioOutline).examples
                        .stream()
                        .flatMap { e -> e.tableBody.stream() }
                        .map { r -> r.location.line }
                        .collect(Collectors.toList())
            }
            val iterationIdx = IntStream.range(0, scenarioOutlineMap[scenarioOutline]!!.size)
                    .filter { i: Int -> line == scenarioOutlineMap[scenarioOutline]!![i] }
                    .findFirst()
                    .orElseThrow {
                        IllegalStateException(String.format("No outline iteration number found for scenario %s",
                                Utils.getCodeRef(featureUri, line)
                        ))
                    }
            outlineIteration = String.format("[%d]", iterationIdx + 1)
        }
    }

    fun processTags(tags: List<String>) {
        attributes = Utils.extractTags(tags)
    }

    fun mapBackgroundSteps(background: Background) {
        scenarioLocationMap.putAll(background.steps.map { it.location.line to it })
    }


    fun getStep(testStep: TestStep): Step {
        val pickleStepTestStep: PickleStepTestStep = testStep as PickleStepTestStep
        val step: Step? = scenarioLocationMap[pickleStepTestStep.step.line]
        if (step != null) {
            return step
        }
        throw IllegalStateException(java.lang.String.format("Trying to get step for unknown line in feature. Scenario: %s, line: %s",
                scenario.name,
                line
        ))
    }

    fun nextBackgroundStep() {
        backgroundSteps.poll()
    }

    private fun isScenarioOutline(scenario: ScenarioDefinition?): Boolean {
        return scenario is ScenarioOutline
    }

    fun withBackground(): Boolean {
        return !backgroundSteps.isEmpty()
    }

    fun hasBackground(): Boolean {
        return hasBackground && background != null
    }

    init {
        throw AssertionError("No instances should exist for the class!")
    }
}