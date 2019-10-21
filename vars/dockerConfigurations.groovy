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

/**
 * Assume that the current directory contains a pom.xml from which we'll extract the version.
 */
def call(configurationName)
{
    def pom = readMavenPom file: 'pom.xml'
    call(configurationName, pom.version)
}

def call(configurationName, xwikiVersion)
{
    def configurations = [:]
    configurations.'docker-latest' = getLatestConfigurations(xwikiVersion)
    configurations.'docker-all' = getAllConfigurations(xwikiVersion)
    configurations.'docker-unsupported' = getUnsupportedConfigurations(xwikiVersion)
    return configurations.get(configurationName)
}

/**
 * Defines the latest versions of supported XWiki configurations.
 */
def getLatestConfigurations(def xwikiVersion)
{
    // Note: For Oracle 1.0.0 corresponds to Oracle 11g Release 2,
    // see https://hub.docker.com/r/oracleinanutshell/oracle-xe-11g/tags
    // In the future we need to find an image using Oracle 12.x since that's what we officially support.
    def configurations = [
        'MySQL 5.7.x, Tomcat 9.x (Java 8), Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk8',
            'browser' : 'chrome',
            'verbose' : 'true',
            'vnc': 'false'
        ],
        'PostgreSQL 11.x, Jetty 9.2.x (Java 8), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.6',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9.2',
            'browser' : 'chrome',
            'verbose' : 'true',
            'vnc': 'false'
        ],
        'HSQLDB Embedded, Jetty Standalone, Firefox': [
            'database' : 'hsqldb_embedded',
            'servletEngine' : 'jetty_standalone',
            'browser' : 'firefox',
            'verbose' : 'true',
            'vnc': 'false'
        ],
        'Oracle 11g Release 2, Tomcat 9.x (Java 8), Firefox': [
            'database' : 'oracle',
            'databaseTag' : '1.0.0',
            'jdbcVersion' : '12.2.0.1',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk8',
            'browser' : 'firefox',
            'verbose' : 'true',
            'vnc': 'false'
        ]
    ]
    return configurations
}

/**
 * Configurations for smoke tests (i.e. only a few tests) on the maximum number of configurations to flush out problems
 * of configurations when XWiki doesn't start or has basic problems. This includes all supported configurations.
 */
def getAllConfigurations(def xwikiVersion)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.5.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8.5',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'MySQL 5.5.x, Tomcat 8.5.x, Firefox': [
            'database' : 'mysql',
            'databaseTag' : '5.5',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8.5',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 11.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.6',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 9.4.x, Jetty 9.x, Firefox': [
            'database' : 'postgresql',
            'databaseTag' : '9.4',
            'jdbcVersion' : '42.2.6',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 9.6.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '9.6',
            'jdbcVersion' : '42.2.6',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'HSQLDB Embedded, Jetty Standalone, Firefox': [
            'database' : 'hsqldb_embedded',
            'servletEngine' : 'jetty_standalone',
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    ]

    // Support for utf8mb4 & for Java 11 is only available from XWiki 11.3RC1+
    // TODO: Merge with config above when LTS becomes 11.x
    if (isXWikiVersionGreaterThan(xwikiVersion, '11', '3')) {
        configurations.'MySQL 5.7.x (utf8mb4), Tomcat 9.x (Java 8), Chrome' = [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8mb4',
            'database.commands.collation-server' : 'utf8mb4_bin',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ]
        // TODO: move that to Tomcat 9
        configurations.'MySQL 5.7.x, Tomcat 9.x (Java 11), Firefox' = [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9',
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    }
    return configurations
}

/**
 * Coonfigurations for smoke tests (i.e. only a few tests) on configurations that we'll want to support in the future
 * but that are currently not supported or not working.
 */
def getUnsupportedConfigurations(def xwikiVersion)
{
    def configurations = [
        // Test on latest MySQL 8.x.
        'MySQL 8.x, Tomcat 9.x (Java 8), Chrome': [
            'database' : 'mysql',
            'databaseTag' : '8',
            'jdbcVersion' : '8.0.16',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Test on latest MySQL 8.x with 5.x connector.
        'MySQL 8.x, Tomcat 9.x (Java 8), Chrome': [
            'database' : 'mysql',
            'databaseTag' : '8',
            'jdbcVersion' : '5.1.47',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ]
    ]
    return configurations
}

private def isXWikiVersionGreaterThan(xwikiVersion, major, minor)
{
    def result
    if (xwikiVersion) {
        def versionParts = xwikiVersion?.split('\\.')
        if (versionParts[0] >= '11' && versionParts[1] >= '3') {
            result = true
        } else {
            result = false
        }
    } else {
        result = true
    }
    return result
}
