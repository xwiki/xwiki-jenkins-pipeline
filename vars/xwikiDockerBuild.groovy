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

void call(body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // If no modules are passed, then find all modules containing docker tests.
    def modules = config.modules ?: getDockerModules()

    // Run docker tests on all modules for all supported configurations
    config.configurations.eachWithIndex() { testConfig, i ->
        def systemProperties = []
        testConfig.value.each() { paramName, value ->
            systemProperties.add("-Dxwiki.test.ui.${paramName}=${value}")
        }
        def testConfigurationName = getTestConfigurationName(testConfig.value)
        // Only execute maven with -U for the first Maven builds since XWiki SNAPSHOT dependencies don't change with
        // configurations.
        // Only clean for the first execution since we don't need to clean more.
        def flags = '-e'
        if (i == 0) {
            flags = "${flags} -U"
        }
        modules.eachWithIndex() { modulePath, j ->
            def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
            def profiles = 'docker,legacy,integration-tests,snapshotModules'
            def commonProperties =
                '-Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true'
            // On the first execution inside this module, build the ui module to be sure we get fresh artifacts
            // downloaded in the local repository. This is needed because when XWikiDockerExtension executes we
            // only resolve from the local repository to speed up the test execution.
            // Note 1: we don't need to build the POM module since it's the parent of the -ui and docker submodules.
            // Note 2: we also don't need to build the pageobjects modules since it's built by the standard platform
            // jobs (i.e. built by -Pintegration-tests).
            if (i == 0) {
                def exists = fileExists "${modulePath}/${moduleName}-ui/pom.xml"
                if (exists) {
                    build(
                        name: "UI module for ${moduleName}",
                        profiles: profiles,
                        properties: commonProperties,
                        mavenFlags: "--projects ${modulePath}/${moduleName}-ui ${flags}",
                        skipCheckout: true,
                        xvnc: false,
                        goals: 'clean install',
                        skipMail: true
                    )
                }
            }
            // Then run the tests
            // Note: We clean every time since we set the maven.build.dir and specify a directory that depends on the
            // configuration (each config has its own target dir).
            build(
                name: "${testConfig.key} - Docker tests for ${moduleName}",
                profiles: profiles,
                properties:
                  "${commonProperties} -Dmaven.build.dir=target/${testConfigurationName} ${systemProperties.join(' ')}",
                mavenFlags: "--projects ${modulePath}/${moduleName}-test/${moduleName}-test-docker ${flags}",
                skipCheckout: true,
                xvnc: false,
                goals: 'clean verify',
                skipMail: config.skipMail
            )
        }
    }
}

/**
 * Find all modules named -test-docker to located docker-based tests.
 */
@NonCPS
private def getDockerModules()
{
    def modules = []
    def dockerModuleFiles = findFiles(glob: '**/*-test-docker/pom.xml')
    dockerModuleFiles.each() {
        // Skip 'xwiki-platform-test-docker' since it matches the glob pattern but isn't a test module.
        if (!it.path.contains('xwiki-platform-test-docker')) {
            // Find grand parent module, e.g. return the path to xwiki-platform-menu when
            // xwiki-platform-menu-test-docker is found.
            modules.add(getParentPath(getParentPath(getParentPath(it.path))))
        }
    }
    return modules
}

private def getParentPath(def path)
{
    return path.substring(0, path.lastIndexOf('/'))
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
    }
}
