#!/usr/bin/env groovy

/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

void call(boolean isParallel = true, body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    echoXWiki "Configurations to execute: ${config.configurations}"
    echoXWiki "Modules to execute: ${config.modules}"

    // Mark build as a Docker build in the Jenkins UI to differentiate it from others "standard" builds
    def badgeText = 'Docker Build'
    def badgeFound = isBadgeFound(badgeText)
    if (!badgeFound) {
        manager.addInfoBadge(badgeText)
        manager.createSummary('green.gif').appendText("${badgeText}", false, false, false, 'green')
        saveCurrentBuildChanges()
    }

    // Start by building the test framework in case there have been recent changes not yet push to the Maven remote
    // repo.
    buildTestFramework()

    // Run docker tests on all modules for all supported configurations
    // Note: We don't rebuild the -pageobjects modules for performance reasons and because any commit in a -pageobjects
    // module will have triggered the main build (which rebuilds the -pageobjects modules). This could fail if the main
    // build fails before reaching the -pageobects with the changes but the likelihood is low and we consider that the
    // tradeoff is acceptable.
    // Pre-compute the data for each configuration (its key, the directory-friendly name used for its Maven build
    // directory, and the list of system properties to pass to Maven) once, so that it can be reused for each module.
    def configurationData = []
    config.configurations.eachWithIndex() { testConfig, i ->
        def systemProperties = []
        // Note: don't use each() since it leads to unserializable exceptions
        for (def entry in testConfig.value) {
            systemProperties.add("-Dxwiki.test.ui.${entry.key}=${entry.value}")
        }

        // Execute WCAG tests for the 1st configuration only (executing on several configurations would not result in
        // additional validations but would cost a lot more in time spent).
        // Note that we don't execute WCAG tests on the standard build (ie the non-environment tests build) since that
        // would mean executing the WCAG tests at each commit and they take too long to execute and would lengthen a
        // a lot the build. Running them on "docker-latest" means executing WCAG tests only once per day.
        if (i == 0 && config.type == 'docker-latest') {
            systemProperties.add('-Dxwiki.test.ui.wcag=true')
            if (env.BRANCH_NAME == 'stable-16.1.x') { systemProperties.add('-Dxwiki.test.ui.wcagStopOnError=false') }
        }

        configurationData.add([
            key: testConfig.key,
            name: getTestConfigurationName(testConfig.value),
            systemProperties: systemProperties
        ])
    }

    // Partition the modules into the large modules (each configuration of which is executed on its own agent) and the
    // other modules (all configurations of which are grouped together on a single agent). See isLargeDockerTestModule()
    // for the rationale.
    def largeModules = config.modules.findAll { isLargeDockerTestModule(it) }
    def otherModules = config.modules.findAll { !isLargeDockerTestModule(it) }
    echoXWiki "Large modules (one agent per configuration): ${largeModules}"
    echoXWiki "Other modules (all configurations grouped on one agent): ${otherModules}"

    def builds = [:]

    // For each non-large module, run all configurations one after the other on a single agent. This amortizes the cost
    // of acquiring an agent, checking out the sources and pulling Docker images, and lets Maven reuse its local
    // repository (downloaded dependencies) across all the configurations of the module: only the first configuration
    // refreshes the dependencies from the remote repository (see -U handling in buildModuleConfigurations()).
    otherModules.each() { modulePath ->
        def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
        builds["Docker tests for ${moduleName}"] = {
            buildModuleConfigurations(modulePath, configurationData, config)
        }
    }

    // For each large module, run each configuration on its own agent (in parallel) since grouping all configurations on
    // a single agent would make that agent run for the sum of the durations of all configurations, increasing the
    // minimum build duration too much.
    largeModules.each() { modulePath ->
        def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
        configurationData.each() { configurationDatum ->
            builds["${configurationDatum.key} - Docker tests for ${moduleName}"] = {
                buildModuleConfigurations(modulePath, [configurationDatum], config)
            }
        }
    }

    if (isParallel) {
        parallel builds
    } else {
        builds.each() { key, build ->
            build.call()
        }
    }

}

private def getTestConfigurationName(def testConfig)
{
    def databasePart =
        "${testConfig.database}-${testConfig.databaseTag ?: 'default'}-${testConfig.jdbcVersion ?: 'default'}"
    def servletEnginePart = "${testConfig.servletEngine}-${testConfig.servletEngineTag ?: 'default'}"
    def browserPart = "${testConfig.browser}"
    // Don't add a blob store part if blob store is not specified to avoid having it in unsupported versions of XWiki.
    def blobStorePart = testConfig.blobStore ? "${testConfig.blobStore}-" : ''
    return "${databasePart}-${servletEnginePart}-${blobStorePart}${browserPart}"
}

private void buildTestFramework()
{
    build(
        name: 'Test Framework',
        profiles: 'docker,integration-tests',
        properties: "${getSystemProperties().join(' ')}",
        pom: 'xwiki-platform-core/xwiki-platform-test/pom.xml',
        xvnc: false
    )
}

private def getSystemProperties()
{
    return [
        '-Dxwiki.checkstyle.skip=true',
        '-Dxwiki.surefire.captureconsole.skip=true',
        '-Dxwiki.revapi.skip=true',
        '-Dxwiki.spoon.skip=true',
        '-Dxwiki.enforcer.skip=true'
    ]
}

/**
 * Run the Docker-based tests of a single module for the passed configurations, all on the same Jenkins agent (i.e.
 * inside a single {@code node} and a single {@code xwikiBuild} call). The sources are only checked out once and the
 * configurations reuse the same workspace and Maven local repository; a single post-build stage is executed for all the
 * configurations. Each configuration uses its own Maven build directory ({@code target/<configuration name>}) so that
 * the configurations don't clobber each other's build output.
 */
private void buildModuleConfigurations(def modulePath, def configurationData, def config)
{
    def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
    echoXWiki "Module name: ${moduleName}"
    def mavenProfiles = 'docker,legacy,integration-tests,snapshotModules,distribution,flavor-integration-tests'
    def testModuleName = "${modulePath}/${moduleName}-test/${moduleName}-test-docker"
    // Build one xwikiBuild execution per configuration so that they all run on the same agent.
    def moduleExecutions = []
    configurationData.eachWithIndex() { configurationDatum, index ->
        // Each configuration uses its own Maven build directory so that the configurations don't clobber each other's
        // build output.
        def executionProperties = ["-Dmaven.build.dir=target/${configurationDatum.name}"]
        executionProperties.addAll(getSystemProperties())
        executionProperties.addAll(configurationDatum.systemProperties)
        // Only force a dependency update (-U) for the first configuration: it refreshes the snapshot dependencies from
        // the remote repository (to avoid stale dependencies and to detect dependencies that are no longer available on
        // Nexus). The subsequent configurations run on the same agent and reuse the dependencies that were just
        // downloaded, so we don't pass -U for them to avoid re-checking/re-downloading the same dependencies.
        moduleExecutions.add([
            name: configurationDatum.key,
            properties: executionProperties.join(' '),
            mavenFlags: index == 0 ? '-U' : ''
        ])
    }
    node(config.label ?: 'docker') {
        xwikiBuild("Docker tests for ${moduleName}") {
            // TODO: Remove once https://github.com/testcontainers/testcontainers-java/issues/4203 is fixed.
            mavenOpts = '-Xmx3076m -Xms512m'
            // Javadoc execution is on by default but we don't need it for the docker tests.
            javadoc = false
            goals = 'clean verify'
            profiles = mavenProfiles
            mavenFlags = "--projects ${testModuleName} -e"
            xvnc = false
            // Run all the configurations of the module, one after the other, on this single agent.
            executions = moduleExecutions
            // Keep builds for 30 days since we want to be able to see all builds if there are a lot at a given
            // time, to be able to identify flickers, etc.
            if (isMasterBranch(env.BRANCH_NAME)) {
                daysToKeepStr = '30'
            }
            if (config.skipMail != null) {
                skipMail = config.skipMail
            }
            if (config.jobProperties != null) {
                jobProperties = config.jobProperties
            }
        }
    }
}

private void build(map)
{
    node(map.label) {
        xwikiBuild(map.name) {
            // TODO: Remove once https://github.com/testcontainers/testcontainers-java/issues/4203 is fixed.
            mavenOpts = map.mavenOpts ?: '-Xmx3076m -Xms512m'
            // Javadoc execution is on by default but we don't need it for the docker tests.
            javadoc = false
            if (map.goals != null) {
                goals = map.goals
            }
            if (map.profiles != null) {
                profiles = map.profiles
            }
            if (map.properties != null) {
                properties = map.properties
            }
            if (map.pom != null) {
                pom = map.pom
            }
            if (map.mavenFlags != null) {
                mavenFlags = map.mavenFlags
            }
            if (map.skipCheckout != null) {
                skipCheckout = map.skipCheckout
            }
            if (map.xvnc != null) {
                xvnc = map.xvnc
            }
            if (map.skipMail != null) {
                skipMail = map.skipMail
            }
            if (map.jobProperties != null) {
                jobProperties = map.jobProperties
            }
        }
    }
}
