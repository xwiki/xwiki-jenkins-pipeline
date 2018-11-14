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

// Execute docker functional tests on all supported configurations, according to:
// - DBs: https://dev.xwiki.org/xwiki/bin/view/Community/DatabaseSupportStrategy
// - Servlet containers: https://dev.xwiki.org/xwiki/bin/view/Community/ServletContainerSupportStrategy/
// - Browsers: https://dev.xwiki.org/xwiki/bin/view/Community/BrowserSupportStrategy
// Note that for browsers we're constrained to use the version of them supported by the Selenium version we use. Our
// strategy is to always use the latest released Selenium version in order to use the latest browser versions.
def configurations = [
    'MySQL 5.7.x, Tomcat 8.x, Chrome': [
        'database' : 'mysql',
        'databaseTag' : '5.7',
        'jdbcVersion' : '5.1.45',
        'servletEngine' : 'tomcat',
        'servletEngineTag' : '8.5',
        'browser' : 'chrome'
    ],
    'PostgreSQL 11.x, Jetty 9.x, Chrome': [
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

node('docker') {
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
        name: "Minimal WAR Dependencies",
        profiles: 'distribution',
        mavenFlags: "--projects org.xwiki.platform:xwiki-platform-distribution-war-minimaldependencies -U -e",
        skipCheckout: true,
        xvnc: false,
        goals: "clean install"
    )

    // Find all modules named -test-docker to located docker-based tests
    def modules = []
    def dockerModuleFiles = findFiles(glob: '**/*-test-docker/pom.xml')
    dockerModuleFiles.each() {
        if (!it.path.contains('xwiki-platform-test-docker')) {
            // Find parent module and build it
            def directory = it.path.substring(0, it.path.lastIndexOf("/"))
            def parent = directory.substring(0, directory.lastIndexOf("/"))
            modules.add(parent)
        }
    }

    // Run docker tests on all modules for all supported configurations
    configurations.eachWithIndex() { config, i ->
        def systemProperties = []
        config.value.each() { paramName, value ->
            systemProperties.add("-Dxwiki.test.ui.${paramName}=${value}")
        }
        // Only execute maven with -U for the first Maven builds since XWiki SNAPSHOT dependencies don't change with
        // configurations.
        def flags = "-e"
        if (i == 0) {
            flags = "${flags} -U"
        }
        modules.each() { modulePath ->
            build(
                name: "${config.key} - ${modulePath.substring(modulePath.lastIndexOf("/") + 1, modulePath.length())}",
                profiles: 'docker,legacy,integration-tests,office-tests,snapshotModules',
                properties: "-Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true ${systemProperties.join(' ')}",
                mavenFlags: "--projects ${modulePath} -amd ${flags}",
                skipCheckout: true,
                xvnc: false,
                goals: "clean verify"
            )
        }
    }
}

def build(map)
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
    }
}
