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
    def profiles = 'docker,legacy,integration-tests,snapshotModules,distribution,flavor-integration-tests'
    def batchSize = config.batchSize ?: 4

    // Partition the modules into the large modules (each built on its own agent) and the other modules (grouped in
    // batches built several modules per agent, for each configuration). See isLargeDockerTestModule() for the rationale.
    def largeModules = config.modules.findAll { isLargeDockerTestModule(it) }
    def otherModules = config.modules.findAll { !isLargeDockerTestModule(it) }
    echoXWiki "Large modules (one agent each per configuration): ${largeModules}"
    echoXWiki "Other modules (batched per configuration): ${otherModules}"

    def builds = [:]
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

        def testConfigurationName = getTestConfigurationName(testConfig.value)
        def additionalSystemProperties = ["-Dmaven.build.dir=target/${testConfigurationName}"]
        additionalSystemProperties.addAll(getSystemProperties())
        def allProperties = "${additionalSystemProperties.join(' ')} ${systemProperties.join(' ')}"

        // Register a build of the passed modules (given by their top module path) on a single agent for this
        // configuration. We pass --fail-at-end so that a failure while building one module doesn't prevent the other
        // modules built on the same agent from being built and tested (this is a no-op when there's a single module).
        def registerBuild = { buildName, modulePaths ->
            def testModules = modulePaths.collect {
                def moduleName = it.substring(it.lastIndexOf('/') + 1)
                "${it}/${moduleName}-test/${moduleName}-test-docker"
            }
            builds[buildName] = {
                build(
                    name: buildName,
                    profiles: profiles,
                    properties: allProperties,
                    mavenFlags: "--projects ${testModules.join(',')} -e -U --fail-at-end",
                    xvnc: false,
                    goals: 'clean verify',
                    skipMail: config.skipMail,
                    jobProperties: config.jobProperties,
                    label: config.label ?: 'docker',
                    // Keep builds for 30 days since we want to be able to see all builds if there are a lot at a given
                    // time, to be able to identify flickers, etc.
                    daysToKeepStr: isMasterBranch(env.BRANCH_NAME) ? '30' : null
                )
            }
        }

        // Build the non-large modules in batches, each batch being built on a single agent for this configuration. This
        // amortizes the cost of acquiring an agent, checking out the sources and pulling Docker images over several
        // modules, and lets Maven reuse its local repository across all the modules of a batch.
        otherModules.collate(batchSize).eachWithIndex() { batch, j ->
            def batchName = "${testConfig.key} - Docker tests batch ${j + 1} " +
                "(${batch.collect { it.substring(it.lastIndexOf('/') + 1) }.join(', ')})"
            registerBuild(batchName, batch)
        }

        // Build each large module on its own agent (unbatched) since they take a long time to execute and batching them
        // would increase the minimum build duration.
        largeModules.each() { modulePath ->
            def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
            registerBuild("${testConfig.key} - Docker tests for ${moduleName}", [modulePath])
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
