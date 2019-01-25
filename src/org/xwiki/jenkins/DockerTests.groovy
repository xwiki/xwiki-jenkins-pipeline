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
 * Execute all docker functional tests on latest versions of supported configurations, according to:
 * <ul>
 * <li>DBs: https://dev.xwiki.org/xwiki/bin/view/Community/DatabaseSupportStrategy</li>
 * <li>Servlet containers: https://dev.xwiki.org/xwiki/bin/view/Community/ServletContainerSupportStrategy/</li>
 * <li>Browsers: https://dev.xwiki.org/xwiki/bin/view/Community/BrowserSupportStrategy</li>
 * </ul>
 * <p>
 * Note that for browsers we're constrained to use the version of them supported by the Selenium version we use. Our
 * strategy is to always use the latest released Selenium version in order to use the latest browser versions.
 * <p>
 * TODO: In the future replace this by Java code located in xwiki-platform-test-docker when JUnit5 supports this.
 * (see https://github.com/junit-team/junit5/issues/871).
 * It'll bring the following advantages:
 * <ul>
 * <li>Less reliance on the CI. If we need to get away from Jenkins for ex, it'll make it easier. In general we need to
 *   have the maximum done in the Maven build and the minimum in CI scripts.</li>
 * <li>Ability to run several configs at once on developer's machines.</li>
 * <li>Ability to have a single Maven build executed in the CI and thus not get tons of mails whenever a test fails
 *   (which is the current situation).</li>
 * </ul>
 * Disadvantages:
 * <ul>
 * <li>Ability to paralellize, i.e. execute each Maven build on a different CI agent. This is because the withMaven()
 *   step doesn't currently support this feature (which is a pity). See also
 *   https://massol.myxwiki.org/xwiki/bin/view/Blog/Jenkins%20and%20large%20Maven%20projects<li>
 * </ul>
 * <p>
 * Example usage:
 * <code><pre>
 *   import org.xwiki.jenkins.DockerTests
 *   node('docker') {
 *     new DockerTests().executeDockerSupportedTests()
 *   }
 * </pre></code>
 *
 * @param branch the branch to build (e.g. "master", "stable-10.10.x")
 */
void executeDockerSupportedTests(def branch)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.5.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8.5',
            'browser' : 'chrome'
        ],
        'PostgreSQL 11.x, Jetty 9.2.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9.2',
            'browser' : 'chrome'
        ],
        'HSQLDB Embedded, Jetty Standalone, Firefox': [
            'database' : 'hsqldb_embedded',
            'servletEngine' : 'jetty_standalone',
            'browser' : 'firefox'
        ]
    ]
    executeDockerTests(branch, configurations, null, false)
}

/**
 * Execute smoke tests (i.e. only a few tests) on the maximum number of configurations to flush out problems of
 * configurations when XWiki doesn't start or has basic problems. This includes all supported configurations.
 *
 * @param branch the branch to build (e.g. "master", "stable-10.10.x")
 */
void executeDockerAllTests(def branch)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome'
        ],
        'MySQL 5.5.x, Tomcat 8.x, Firefox': [
            'database' : 'mysql',
            'databaseTag' : '5.5',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome'
        ],
        'PostgreSQL 11.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome'
        ],
        'PostgreSQL 9.4.x, Jetty 9.x, Firefox': [
            'database' : 'postgresql',
            'databaseTag' : '9.4',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome'
        ],
        'PostgreSQL 9.6.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '9.6',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome'
        ],
        'HSQLDB Embedded, Jetty Standalone, Firefox': [
            'database' : 'hsqldb_embedded',
            'servletEngine' : 'jetty_standalone',
            'browser' : 'firefox'
        ]
    ]

    // Smoke test modules.
    def modules = [
        "xwiki-platform-core/xwiki-platform-menu"
    ]
    executeDockerTests(branch, configurations, modules, false)
}

/**
 * Execute smoke tests (i.e. only a few tests) on configurations that we'll want to support in the future but that
 * are currently not supported or not working.
 *
 * @param branch the branch to build (e.g. "master", "stable-10.10.x")
 */
void executeDockerUnsupportedTests(def branch)
{
    def configurations = [
        'MySQL 8.x, Tomcat 8.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '8',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'MySQL 8.x, Tomcat 9.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '8',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ]
    ]

    // Smoke test modules.
    def modules = [
        "xwiki-platform-core/xwiki-platform-menu"
    ]
    executeDockerTests(branch, configurations, modules, true)
}

/**
 * Example usage:
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
 *     new DockerTests().executeDockerTests(configurations, null)
 *   }
 * </pre></code>
 *
 * @param branch the branch to build (e.g. "master", "stable-10.10.x")
 * @param configurations the configurations for which to execute the functional tests defined in the passed modules
 * @param modules the modules on which to run the tests
 * @param skipMail if true then don't send emails when builds fail
 */
void executeDockerTests(def branch, def configurations, def modules, def skipMail)
{
    // Checkout platform
    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${branch}"]],
        doGenerateSubmoduleConfigurations: false,
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: branch]],
        submoduleCfg: [],
        userRemoteConfigs: [[url: 'https://github.com/xwiki/xwiki-platform.git']]])

    dir(branch) {
        buildAndExecuteDockerTest(configurations, modules, skipMail, branch)
    }
}

private void buildAndExecuteDockerTest(def configurations, def modules, def skipMail, def branch)
{
    sh script: 'locale', returnStatus: true

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
            if (!it.path.contains('xwiki-platform-test-docker')) {
                // Find great parent module, e.g. return the path to xwiki-platform-menu when
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
            // jobs.
            if (i == 0) {
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