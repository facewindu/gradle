/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.internal.tasks.compile;

import com.google.common.collect.Iterables;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit;
import org.codehaus.groovy.tools.javac.JavaCompiler;
import org.codehaus.groovy.tools.javac.JavaCompilerFactory;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.classloading.GroovySystemLoader;
import org.gradle.api.internal.classloading.GroovySystemLoaderFactory;
import org.gradle.api.internal.file.collections.SimpleFileCollection;
import org.gradle.api.internal.tasks.SimpleWorkResult;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.classloader.DefaultClassLoaderFactory;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.util.VersionNumber;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class ApiGroovyCompiler implements org.gradle.language.base.internal.compile.Compiler<GroovyJavaJointCompileSpec>, Serializable {
    private final Compiler<JavaCompileSpec> javaCompiler;

    public ApiGroovyCompiler(Compiler<JavaCompileSpec> javaCompiler) {
        this.javaCompiler = javaCompiler;
    }

    public WorkResult execute(final GroovyJavaJointCompileSpec spec) {
        GroovySystemLoaderFactory groovySystemLoaderFactory = new GroovySystemLoaderFactory();
        ClassLoader compilerClassLoader = this.getClass().getClassLoader();
        GroovySystemLoader compilerGroovyLoader = groovySystemLoaderFactory.forClassLoader(compilerClassLoader);

        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setVerbose(spec.getGroovyCompileOptions().isVerbose());
        configuration.setSourceEncoding(spec.getGroovyCompileOptions().getEncoding());
        configuration.setTargetBytecode(spec.getTargetCompatibility());
        configuration.setTargetDirectory(spec.getDestinationDir());
        canonicalizeValues(spec.getGroovyCompileOptions().getOptimizationOptions());
        if (spec.getGroovyCompileOptions().getConfigurationScript() != null) {
            applyConfigurationScript(spec.getGroovyCompileOptions().getConfigurationScript(), configuration);
        }
        try {
            configuration.setOptimizationOptions(spec.getGroovyCompileOptions().getOptimizationOptions());
        } catch (NoSuchMethodError ignored) { /* method was only introduced in Groovy 1.8 */ }
        Map<String, Object> jointCompilationOptions = new HashMap<String, Object>();
        final File stubDir = spec.getGroovyCompileOptions().getStubDir();
        stubDir.mkdirs();
        jointCompilationOptions.put("stubDir", stubDir);
        jointCompilationOptions.put("keepStubs", spec.getGroovyCompileOptions().isKeepStubs());
        configuration.setJointCompilationOptions(jointCompilationOptions);

        ClassLoader classPathLoader;
        VersionNumber version = parseGroovyVersion();
        if (version.compareTo(VersionNumber.parse("2.0")) < 0) {
            // using a transforming classloader is only required for older buggy Groovy versions
            classPathLoader = new GroovyCompileTransformingClassLoader(getExtClassLoader(), new DefaultClassPath(spec.getClasspath()));
        } else {
            classPathLoader = new DefaultClassLoaderFactory().createIsolatedClassLoader(new DefaultClassPath(spec.getClasspath()));
        }
        GroovyClassLoader compileClasspathClassLoader = new GroovyClassLoader(classPathLoader, null);
        GroovySystemLoader compileClasspathLoader = groovySystemLoaderFactory.forClassLoader(classPathLoader);

        FilteringClassLoader groovyCompilerClassLoader = new FilteringClassLoader(GroovyClassLoader.class.getClassLoader());
        groovyCompilerClassLoader.allowPackage("org.codehaus.groovy");
        groovyCompilerClassLoader.allowPackage("groovy");
        // Disallow classes from Groovy Jar that reference external classes. Such classes must be loaded from astTransformClassLoader,
        // or a NoClassDefFoundError will occur. Essentially this is drawing a line between the Groovy compiler and the Groovy
        // library, albeit only for selected classes that run a high risk of being statically referenced from a transform.
        groovyCompilerClassLoader.disallowClass("groovy.util.GroovyTestCase");
        groovyCompilerClassLoader.disallowPackage("groovy.servlet");

        // AST transforms need their own class loader that shares compiler classes with the compiler itself
        final GroovyClassLoader astTransformClassLoader = new GroovyClassLoader(groovyCompilerClassLoader, null);
        // can't delegate to compileClasspathLoader because this would result in ASTTransformation interface
        // (which is implemented by the transform class) being loaded by compileClasspathClassLoader (which is
        // where the transform class is loaded from)
        for (File file : spec.getClasspath()) {
            astTransformClassLoader.addClasspath(file.getPath());
        }
        JavaAwareCompilationUnit unit = new JavaAwareCompilationUnit(configuration, compileClasspathClassLoader) {
            @Override
            public GroovyClassLoader getTransformLoader() {
                return astTransformClassLoader;
            }
        };

        final boolean shouldProcessAnnotations = shouldProcessAnnotations(astTransformClassLoader, spec);
        if (shouldProcessAnnotations) {
            // If an annotation processor is detected, we need to force Java stub generation, so the we can process annotations on Groovy classes
            // We are forcing stub generation by tricking the groovy compiler into thinking there are java files to compile.
            // All java files are just passed to the compile method of the JavaCompiler and aren't processed internally by the Groovy Compiler.
            // Since we're maintaining our own list of Java files independent what's passed by the Groovy compiler, adding a non-existant java file
            // to the sources won't cause any issues.
            unit.addSources(new File[]{new File("ForceStubGeneration.java")});
        }

        unit.addSources(Iterables.toArray(spec.getSource(), File.class));
        unit.setCompilerFactory(new JavaCompilerFactory() {
            public JavaCompiler createCompiler(final CompilerConfiguration config) {
                return new JavaCompiler() {
                    public void compile(List<String> files, CompilationUnit cu) {
                        if (shouldProcessAnnotations) {
                            // In order for the Groovy stubs to have annotation processors invoked against them, they must be compiled as source.
                            // Classes compiled as a result of being on the -sourcepath do not have the annotation processor run against them
                            spec.setSource(spec.getSource().plus(new SimpleFileCollection(stubDir).getAsFileTree()));
                        } else {
                            // When annotation processing isn't required, it's better to add the Groovy stubs as part of the source path.
                            // This allows compilations to complete faster, because only the Groovy stubs that are needed by the java source are compiled.
                            FileCollection sourcepath = new SimpleFileCollection(stubDir);
                            if (spec.getCompileOptions().getSourcepath() != null) {
                                sourcepath = spec.getCompileOptions().getSourcepath().plus(sourcepath);
                            }
                            spec.getCompileOptions().setSourcepath(sourcepath);
                        }

                        spec.setSource(spec.getSource().filter(new Spec<File>() {
                            public boolean isSatisfiedBy(File file) {
                                return file.getName().endsWith(".java");
                            }
                        }));

                        try {
                            javaCompiler.execute(spec);
                        } catch (CompilationFailedException e) {
                            cu.getErrorCollector().addFatalError(new SimpleMessage(e.getMessage(), cu));
                        }
                    }
                };
            }
        });

        try {
            unit.compile();
        } catch (org.codehaus.groovy.control.CompilationFailedException e) {
            System.err.println(e.getMessage());
            throw new CompilationFailedException();
        } finally {
            // Remove compile and AST types from the Groovy loader
            compilerGroovyLoader.discardTypesFrom(classPathLoader);
            compilerGroovyLoader.discardTypesFrom(astTransformClassLoader);
            //Discard the compile loader
            compileClasspathLoader.shutdown();
        }

        return new SimpleWorkResult(true);
    }

    private boolean shouldProcessAnnotations(ClassLoader classLoader, GroovyJavaJointCompileSpec spec) {
        return !isAnnotationProcessingDisabled(spec)
            && (isAnnotationProcessorOnClasspath(classLoader) || isDefaultAnnotationProcessorDiscoveryOverridden(spec));
    }

    private boolean isAnnotationProcessingDisabled(GroovyJavaJointCompileSpec spec) {
        List<String> compilerArgs = spec.getCompileOptions().getCompilerArgs();
        return !spec.getGroovyCompileOptions().isJavaAnnotationProcessing() || compilerArgs.contains("-proc:none");
    }

    private boolean isAnnotationProcessorOnClasspath(ClassLoader classLoader) {
        try {
            Enumeration<URL> processorEntries = classLoader.getResources("META-INF/services/javax.annotation.processing.Processor");
            return processorEntries.hasMoreElements();
        } catch (IOException e) {
            throw new GradleException("Failed to retrieve annotation processor metadata from classpath", e);
        }
    }

    private boolean isDefaultAnnotationProcessorDiscoveryOverridden(GroovyJavaJointCompileSpec spec) {
        List<String> compilerArgs = spec.getCompileOptions().getCompilerArgs();
        return !Collections.disjoint(compilerArgs, Arrays.asList("-processorpath", "-processor"));
    }

    private void applyConfigurationScript(File configScript, CompilerConfiguration configuration) {
        VersionNumber version = parseGroovyVersion();
        if (version.compareTo(VersionNumber.parse("2.1")) < 0) {
            throw new GradleException("Using a Groovy compiler configuration script requires Groovy 2.1+ but found Groovy " + version + "");
        }
        Binding binding = new Binding();
        binding.setVariable("configuration", configuration);

        CompilerConfiguration configuratorConfig = new CompilerConfiguration();
        ImportCustomizer customizer = new ImportCustomizer();
        customizer.addStaticStars("org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder");
        configuratorConfig.addCompilationCustomizers(customizer);

        GroovyShell shell = new GroovyShell(binding, configuratorConfig);
        try {
            shell.evaluate(configScript);
        } catch (Exception e) {
            throw new GradleException("Could not execute Groovy compiler configuration script: " + configScript.getAbsolutePath(), e);
        }
    }

    private VersionNumber parseGroovyVersion() {
        String version;
        try {
            version = GroovySystem.getVersion();
        } catch (NoSuchMethodError e) {
            // for Groovy <1.6, we need to call org.codehaus.groovy.runtime.InvokerHelper#getVersion
            try {
                Class<?> ih = Class.forName("org.codehaus.groovy.runtime.InvokerHelper");
                Method getVersion = ih.getDeclaredMethod("getVersion");
                version = (String) getVersion.invoke(ih);
            } catch (Exception e1) {
                throw new GradleException("Unable to determine Groovy version.", e1);
            }
        }
        return VersionNumber.parse(version);
    }

    // Make sure that map only contains Boolean.TRUE and Boolean.FALSE values and no other Boolean instances.
    // This is necessary because:
    // 1. serialization/deserialization of the compile spec doesn't preserve Boolean.TRUE/Boolean.FALSE but creates new instances
    // 1. org.codehaus.groovy.classgen.asm.WriterController makes identity comparisons
    private void canonicalizeValues(Map<String, Boolean> options) {
        for (String key : options.keySet()) {
            // unboxing and boxing does the trick
            boolean value = options.get(key);
            options.put(key, value);
        }
    }

    private ClassLoader getExtClassLoader() {
        return new DefaultClassLoaderFactory().getIsolatedSystemClassLoader();
    }
}
