/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.archive.JarTestFixture

class CustomJarBinarySpecSubtypeIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile << """
            plugins {
                id 'jvm-component'
            }

            @Managed
            interface CustomJarBinarySpec extends JarBinarySpec {
                String getValue()
                void setValue(String value)
            }

            ${registerBinaryType("CustomJarBinarySpec")}
        """
    }

    def "can create a Jar from a managed JarBinarySpec subtype"() {
        given:
        buildFile << """
            model {
                components {
                    sampleLib(JvmLibrarySpec) {
                        binaries {
                            customJar(CustomJarBinarySpec) {
                                value = "12"
                            }
                        }
                    }
                }
            }
        """
        expect:
        succeeds "customJar"
        new JarTestFixture(file("build/jars/customJar/sampleLib.jar")).isManifestPresentAndFirstEntry()
    }

    def "managed JarBinarySpec subtypes can have further subtypes"() {
        given:
        buildFile << """
            @Managed
            interface CustomParentJarBinarySpec extends CustomJarBinarySpec {
                String getParentValue()
                void setParentValue(String value)
            }

            @Managed
            interface CustomChildJarBinarySpec extends CustomParentJarBinarySpec {
                String getChildValue()
                void setChildValue(String value)
            }

            ${registerBinaryType("CustomChildJarBinarySpec")}

            model {
                components {
                    sampleLib(JvmLibrarySpec) {
                        binaries {
                            customJar(CustomChildJarBinarySpec) {
                                value = "12"
                                parentValue = "Lajos"
                                childValue = "Tibor"
                            }
                        }
                    }
                }
            }
        """
        expect:
        succeeds "customJar"
        new JarTestFixture(file("build/jars/customJar/sampleLib.jar")).isManifestPresentAndFirstEntry()
    }

    def registerBinaryType(String binaryType) {
        return """
            import org.gradle.jvm.platform.internal.DefaultJavaPlatform

            class ${binaryType}Rules extends RuleSource {
                @BinaryType
                void customJarBinary(BinaryTypeBuilder<${binaryType}> builder) {
                }

                @Finalize
                void setToolChainsForBinaries(ModelMap<BinarySpec> binaries) {
                    def platform = DefaultJavaPlatform.current()
                    binaries.withType(${binaryType}).beforeEach { binary ->
                        binary.targetPlatform = platform
                    }
                }
            }

            apply plugin: ${binaryType}Rules
        """
    }
}