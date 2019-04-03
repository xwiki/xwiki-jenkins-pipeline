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
package org.xwiki.jenkins

/**
 * Example usage 1:
 * <code><pre>
 *   import org.xwiki.jenkins.DockerTests
 *   def configurations = [
 *       'MySQL 5.7.x, Tomcat 8.x, Chrome': [
 *           'database' : 'mysql',
 *           'databaseTag' : '5.7',
 *           'jdbcVersion' : '5.1.45',
 *           'servletEngine' : 'tomcat',
 *           'servletEngineTag' : '8.5',
 *           'browser' : 'chrome'
 *       ]
 *   node('docker') {
 *     checkout scm
 *     new DockerTests().executeDockerTests(configurations, null, true)
 *   }
 * </pre></code>
 *
 * Example usage 2:
 * <code><pre>
 *   import org.xwiki.jenkins.*
 *   node('docker') {
 *     checkout scm
 *     def configs = new DockerConfigurations().getLatestConfigurations('master')
 *     new DockerTests().execute(configs, null, true)
 *   }
 * </pre></code>
 *
 * @param configurations the configurations for which to execute the functional tests defined in the passed modules
 * @param modules the modules on which to run the tests
 * @param skipMail if true then don't send emails when builds fail
 */
void execute(def configurations, def modules, def skipMail)
{
    // Build xwiki-platform-docker test framework since we use it and we happen to make changes to it often and thus
    // if we don't build it here, we have to wait for the full xwiki-platform to be built before being able to run
    // the docker tests again. It can also lead to build failures since this method is called during scheduled jobs
    // which could be triggered before xwiki-platform-docker has been rebuilt.
    build(
        name: 'Docker Test Framework',
        profiles: 'docker,integration-tests',
        mavenFlags: '--projects org.xwiki.platform:xwiki-platform-test-docker -U -e',
        skipCheckout: true,
        xvnc: false,
        cron: 'none',
        goals: 'clean install',
        skipMail: skipMail
    )

    // Build the minimal war module to make sure we have the latest dependencies present in the local maven repo
    // before we run the docker tests. By default the Docker-based tests resolve the minimal war deps from the local
    // repo only without going online.
    build(
        name: 'Minimal WAR Dependencies',
        mavenFlags: '--projects org.xwiki.platform:xwiki-platform-minimaldependencies -U -e',
        skipCheckout: true,
        xvnc: false,
        cron: 'none',
        goals: 'clean install',
        skipMail: skipMail
    )

    // If no modules are passed, then find all modules containing docker tests.
    // Find all modules named -test-docker to located docker-based tests
    if (!modules || modules.isEmpty()) {
        modules = []
        def dockerModuleFiles = findFiles(glob: '**/*-test-docker/pom.xml')
        dockerModuleFiles.each() {
            // Skip 'xwiki-platform-test-docker' since it matches the glob pattern but isn't a test module.
            if (!it.path.contains('xwiki-platform-test-docker')) {
                // Find grand parent module, e.g. return the path to xwiki-platform-menu when
                // xwiki-platform-menu-test-docker is found.
                modules.add(getParentPath(getParentPath(getParentPath(it.path))))
            }
        }
    }

    // Run docker tests on all modules for all supported configurations
    configurations.eachWithIndex() { config, i ->
        def systemProperties = []
        config.value.each() { paramName, value ->
            systemProperties.add("-Dxwiki.test.ui.${paramName}=${value}")
        }
        def configurationName = getConfigurationName(config.value)
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
                        cron: 'none',
                        goals: 'clean install',
                        skipMail: skipMail
                    )
                }
            }
            // Then run the tests
            // Note: We clean every time since we set the maven.build.dir and specify a directory that depends on the
            // configuration (each config has its own target dir).
            build(
                name: "${config.key} - Docker tests for ${moduleName}",
                profiles: profiles,
                properties:
                    "${commonProperties} -Dmaven.build.dir=target/${configurationName} ${systemProperties.join(' ')}",
                mavenFlags: "--projects ${modulePath}/${moduleName}-test/${moduleName}-test-docker ${flags}",
                skipCheckout: true,
                xvnc: false,
                cron: 'none',
                goals: 'clean verify',
                skipMail: skipMail
            )
        }
    }
}

private def getParentPath(def path)
{
    return path.substring(0, path.lastIndexOf('/'))
}

private def getConfigurationName(def config)
{
    def databasePart = "${config.database}-${config.databaseTag ?: 'default'}-${config.jdbcVersion ?: 'default'}"
    def servletEnginePart = "${config.servletEngine}-${config.servletEngineTag ?: 'default'}"
    def browserPart = "${config.browser}"
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
        if (map.cron != null) {
            cron = map.cron
        }
        if (map.skipMail != null) {
            skipMail = map.skipMail
        }
    }
}

return this