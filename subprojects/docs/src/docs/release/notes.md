## New and noteworthy

Here are the new features introduced in this Gradle release.

<!--
IMPORTANT: if this is a patch release, ensure that a prominent link is included in the foreword to all releases of the same minor stream.
Add-->

<!--
### Example new and noteworthy
-->

### Java software model compile avoidance

This version of Gradle further optimizes on avoiding recompiling consuming libraries after non-ABI breaking changes. Since 2.9, if a library declares an API, Gradle creates a "[stubbed API jar](userguide/java_software.html)". This enables avoiding recompiling any consuming library if the application binary interface (ABI) of the library doesn't change. This version of Gradle extends this functionality to libraries that don't declare their APIs, speeding up builds with incremental changes in most Java projects, small or large. In particular, a library `A` that depend on a library `B` will not need to be recompiled in the following cases:

* a private method is added to `B`
* a method body is changed in `B`
* order of methods is changed in `B`

This feature only works for local libraries, not external dependencies. More information about compile avoidance can be found in the [userguide](userguide/java_software.html).

## Promoted features

Promoted features are features that were incubating in previous versions of Gradle but are now supported and subject to backwards compatibility.
See the User guide section on the “[Feature Lifecycle](userguide/feature_lifecycle.html)” for more information.

The following are the features that have been promoted in this Gradle release.

<!--
### Example promoted
-->

## Fixed issues

## Deprecations

Features that have become superseded or irrelevant due to the natural evolution of Gradle become *deprecated*, and scheduled to be removed
in the next major Gradle version (Gradle 3.0). See the User guide section on the “[Feature Lifecycle](userguide/feature_lifecycle.html)” for more information.

The following are the newly deprecated items in this Gradle release. If you have concerns about a deprecation, please raise it via the [Gradle Forums](http://discuss.gradle.org).

<!--
### Example deprecation
-->

## Potential breaking changes

### Scala plugin no longer adds 'scalaConsole' tasks

Adding the 'scala' plugin to your build will no longer create 'scalaConsole' tasks which launch a Scala REPL from the Gradle build. This capability has been
removed due to lack of documentation and support for running with the Gradle Daemon. If you wish to continue to have such a task as part of your build, you
can explicitly configure a [`JavaExec`](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html) task to do so.

### Eclipse Plugin adds explicit java target runtime to Classpath

The `.classpath` file generated via `eclipseClasspath` task provided by the Eclipse Plugin now points to an explicit Java Runtime Version instead of
using the default JRE configured in the Eclipse IDE. The naming convention follows the Eclipse defaults.
To tweak the name of the Java runtime to use, the name can be configured via

    eclipse {
        jdt {
            javaRuntimeName = "Jigsaw-1.9"
        }
    }

## External contributions

We would like to thank the following community members for making contributions to this release of Gradle.

* [Johnny Lim](https://github.com/izeye) - Documentation improvements
* [Christopher O'Connell](https://github.com/lordoku) - Remove 'scalaConsole' task
* [Illya Gerasymchuk](https://github.com/iluxonchik) - Fix typos in Windows batch scripts

We love getting contributions from the Gradle community. For information on contributing, please see [gradle.org/contribute](http://gradle.org/contribute).

## Known issues

Known issues are problems that were discovered post release that are directly related to changes made in this release.
