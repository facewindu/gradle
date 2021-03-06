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

package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.api.JavaVersion;

import java.io.Serializable;

public class DefaultIdeaModuleJavaSourceSettings implements Serializable {

    private final JavaVersion sourceLanguageLevel;
    private final boolean inherited;

    public DefaultIdeaModuleJavaSourceSettings(JavaVersion sourceLanguageLevel, boolean inherited) {
        this.sourceLanguageLevel = sourceLanguageLevel;
        this.inherited = inherited;
    }

    public boolean isSourceLanguageLevelInherited() {
        return inherited;
    }

    public JavaVersion getSourceLanguageLevel() {
        return sourceLanguageLevel;
    }
}
