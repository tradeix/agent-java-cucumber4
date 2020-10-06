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
package com.epam.reportportal.cucumber.util

import com.epam.reportportal.service.tree.TestItemTree
import com.epam.reportportal.service.tree.TestItemTree.ItemTreeKey
import com.epam.reportportal.service.tree.TestItemTree.TestItemLeaf
import java.net.URI
import java.util.*

/**
 * @author Vadzim Hushchanskou
 */
object ItemTreeUtils {
    fun createKey(key: String): ItemTreeKey {
        return ItemTreeKey.of(key)
    }

    fun createKey(lineNumber: Int): ItemTreeKey {
        return ItemTreeKey.of(lineNumber.toString())
    }

    fun retrieveLeaf(featureUri: URI, testItemTree: TestItemTree): Optional<TestItemLeaf> {
        return Optional.ofNullable(testItemTree.testItems[createKey(featureUri.toString())])
    }

    fun retrieveLeaf(featureUri: URI, lineNumber: Int, testItemTree: TestItemTree): Optional<TestItemLeaf> {
        val suiteLeaf = retrieveLeaf(featureUri, testItemTree)
        return suiteLeaf.map { leaf: TestItemLeaf -> leaf.childItems[createKey(lineNumber)] }
    }

    fun retrieveLeaf(featureUri: URI, lineNumber: Int, text: String, testItemTree: TestItemTree): Optional<TestItemLeaf> {
        val testClassLeaf = retrieveLeaf(featureUri, lineNumber, testItemTree)
        return testClassLeaf.map { leaf: TestItemLeaf -> leaf.childItems[createKey(text)] }
    }
}