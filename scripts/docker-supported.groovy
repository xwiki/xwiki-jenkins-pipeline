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

// Execute docker functional tests on all supported configurations.

def configurations = [
    'MySQL 5.x, Tomcat 8.x, Chrome': [
        'database' : 'mysql',
        'databaseTag' : '5',
        'servletEngine' : 'tomcat',
        'servletEngineTag' : '8',
        'browser' : 'chrome'
    ],
    'PostgreSQL 9.x, Jetty 9.x, Chrome': [
        'database' : 'postgres',
        'databaseTag' : '9',
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

node('docker') {
    // Checkout platform
    checkout([
        $class: 'GitSCM',
        branches: [[name: '*/master']],
        doGenerateSubmoduleConfigurations: false,
        extensions: [],
        submoduleCfg: [],
        userRemoteConfigs: [[url: 'https://github.com/xwiki/xwiki-platform.git']]])

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
    configurations.each() { configName, parameters ->
        def systemProperties = []
        parameters.each() { paramName, value ->
            systemProperties.add("-Dxwiki.test.ui.${paramName}=${value}")
        }
        modules.each() { modulePath ->
            build(
                name: "${configName} - ${modulePath.substring(modulePath.lastIndexOf("/") + 1, modulePath.length())}",
                profiles: 'docker,legacy,integration-tests,office-tests,snapshotModules',
                properties: "-amd -Dxwiki.checkstyle.skip=true -Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true ${systemProperties.join(' ')}",
                projects: modulePath,
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
        if (map.projects != null) {
            projects = map.projects
        }
        if (map.skipCheckout != null) {
            skipCheckout = map.skipCheckout
        }
        if (map.xvnc != null) {
            xvnc = map.xvnc
        }
    }
}
