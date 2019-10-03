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
import com.cloudbees.groovy.cps.NonCPS
import com.jenkinsci.plugins.badge.action.BadgeAction

void call(boolean isParallel = false, body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    echoXWiki "Configurations to execute: ${config.configurations}"
    echoXWiki "Modules to execute: ${config.modules}"

    // Mark build as a Docker build in the Jenkins UI to differentiate it from others "standard" builds
    def badgeText = 'Docker Build'
    def badgeFound = isBadgeFound(currentBuild.getRawBuild().getActions(BadgeAction.class), badgeText)
    if (!badgeFound) {
        manager.addInfoBadge(badgeText)
        manager.createSummary('green.gif').appendText("<h1>${badgeText}</h1>", false, false, false, 'green')
        currentBuild.rawBuild.save()
    }

    // Run docker tests on all modules for all supported configurations
    def builds = [:]
    config.configurations.eachWithIndex() { testConfig, i ->
        def systemProperties = []
        // Note: don't use each() since it leads to unserializable exceptions
        for (def entry in testConfig.value) {
            systemProperties.add("-Dxwiki.test.ui.${entry.key}=${entry.value}")
        }
        def testConfigurationName = getTestConfigurationName(testConfig.value)
        config.modules.each() { parentModulePath ->
            def parentModuleName =
                parentModulePath.substring(parentModulePath.lastIndexOf('/') + 1, parentModulePath.length())
            echoXWiki "Module name: ${parentModuleName}"
            def profiles = 'docker,legacy,integration-tests,snapshotModules'
            def commonProperties =
                '-Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true'
            builds["${testConfig.key} - Docker tests for ${parentModuleName}"] = {
                build(
                    name: "${testConfig.key} - Docker tests for ${parentModuleName}",
                    profiles: profiles,
                    properties:
                        "${commonProperties} -Dmaven.build.dir=target/${testConfigurationName} ${systemProperties.join(' ')}",
                    parentModulePath: parentModulePath,
                    parentModuleName: parentModuleName,
                    xvnc: false,
                    goals: 'clean verify',
                    skipMail: config.skipMail,
                    jobProperties: config.jobProperties,
                    label: config.label ?: 'docker'
                )
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
    return "${databasePart}-${servletEnginePart}-${browserPart}"
}

private void build(map)
{
    node(map.label) {
        xwikiBuild(map.name) {
            mavenOpts = map.mavenOpts ?: "-Xmx2048m -Xms512m"
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
            mavenFlags = "--projects ${getTestModuleName(map.parentModulePath, map.parentModuleName)} -e -U"
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

private def getTestModuleName(parentModulePath, parentModuleName)
{
    // Try first with a submodule having the same prefix as its parent.
    def testModuleName = "${parentModulePath}/${parentModuleName}-test/${parentModuleName}-test-docker"
    if (!fileExists("${testModuleName}/pom.xml")) {
        // Then, try by removing the last char which could be an 's'
        def singularModuleName = parentModuleName.substring(0, parentModuleName.length() - 1)
        testModuleName = "${parentModulePath}/${singularModuleName}-test/${singularModuleName}-test-docker"
        if (!fileExists("${testModuleName}/pom.xml")) {
            throw new Exception("Cannot find pom.xml at [${testModuleName}]")
        }
    }
    return testModuleName
}

@NonCPS
private def isBadgeFound(def badgeActionItems, def badgeText)
{
    def badgeFound = false
    badgeActionItems.each() {
        if (it.getText().contains(badgeText)) {
            badgeFound = true
            return
        }
    }
    return badgeFound
}
