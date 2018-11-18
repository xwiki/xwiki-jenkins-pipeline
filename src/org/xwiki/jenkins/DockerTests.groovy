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
 */
void executeDockerSupportedTests()
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
    executeDockerTests(configurations, null, false)
}

/**
 * Execute smoke tests (i.e. only a few tests) on the maximum number of configurations to flush out problems of
 * configurations when XWiki doesn't start or has basic problems. This includes all supported configurations.
 */
void executeDockerAllTests()
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
        "xwiki-platform-core/xwiki-platform-menu/xwiki-platform-menu-test"
    ]
    executeDockerTests(configurations, modules, false)
}

/**
 * Execute smoke tests (i.e. only a few tests) on configurations that we'll want to support in the future but that
 * are currently not supported or not working.
 */
void executeDockerUnsupportedTests()
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
        'MySQL 5.x, Tomcat 9.x, Chrome': [
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
        "xwiki-platform-core/xwiki-platform-menu/xwiki-platform-menu-test"
    ]
    executeDockerTests(configurations, modules, true)
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
 * @param configurations the configurations for which to execute the functional tests defined in the passed modules
 * @param modules the modules on which to run the tests
 * @param skipMail if true then don't send emails when builds fail
 */
void executeDockerTests(def configurations, def modules, def skipMail)
{
    // Checkout platform
    checkout([
        $class: 'GitSCM',
        branches: [[name: '*/master']],
        doGenerateSubmoduleConfigurations: false,
        extensions: [],
        submoduleCfg: [],
        userRemoteConfigs: [[url: 'https://github.com/xwiki/xwiki-platform.git']]])

    // Build the minimal war module to make sure we have the latest dependencies present in the local maven repo
    // before we run the docker tests. By default the Docker-based tests resolve the minimal war deps from the local
    // repo only without going online.
    build(
        name: 'Minimal WAR Dependencies',
        profiles: 'distribution',
        mavenFlags: '--projects org.xwiki.platform:xwiki-platform-distribution-war-minimaldependencies -U -e',
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
                // Find parent module and build it
                def directory = it.path.substring(0, it.path.lastIndexOf('/'))
                def parent = directory.substring(0, directory.lastIndexOf('/'))
                modules.add(parent)
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
        def goals = 'verify'
        if (i == 0) {
            flags = "${flags} -U"
            goals = "clean ${goals}"
        }
        modules.eachWithIndex() { modulePath, j ->
            def moduleName = modulePath.substring(modulePath.lastIndexOf('/') + 1, modulePath.length())
            def profiles = 'docker,legacy,integration-tests,office-tests,snapshotModules'
            def commonProperties = '-Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true'
            // On the first execution inside this module, build the pageobjects module.
            // Note: we don't need to build the POM module since it's the parent of pageobjects and docker submodules.
            if (i == 0) {
                build(
                    name: "Pageobjects module for ${moduleName}",
                    profiles: profiles,
                    properties: commonProperties,
                    mavenFlags: "--projects ${modulePath}/${moduleName}-pageobjects ${flags}",
                    skipCheckout: true,
                    xvnc: false,
                    cron: 'none',
                    goals: 'clean install',
                    skipMail: skipMail
                )
            }
            // Then run the tests
            build(
                name: "${config.key} - ${moduleName}",
                profiles: profiles,
                properties: "${commonProperties} -Dmaven.build.dir=target/${configurationName} ${systemProperties.join(' ')}",
                mavenFlags: "--projects ${modulePath}/${moduleName}-docker ${flags}",
                skipCheckout: true,
                xvnc: false,
                cron: 'none',
                goals: goals,
                skipMail: skipMail
            )
        }
    }
}

def getConfigurationName(def config)
{
    return "${config.database}-${config.databaseTag ?: 'default'}-${config.jdbcVersion ?: 'default'}-${config.servletEngine}-${config.servletEngineTag ?: 'default'}-${config.browser}"
}

void build(map)
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