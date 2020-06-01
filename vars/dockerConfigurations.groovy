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

def VERSIONS = [
    'mysql' : [ 'latest' : '8.0', 'lts' : '5.7' ],
    'mariadb' : [ 'latest' : '10.4', 'lts' : '10.3' ],
    'postgresql' : [ 'latest' : '12.3', 'lts' : '11.8', 'debian' : '11.7' ],
    'oracle' : [ 'latest' : '19.3.0-se2' ],
    'tomcat' : [ 'latest' : '9-jdk11', 'lts' : '8.5-jdk8', 'special' : '9-jdk8' ],
    'jetty' : [ 'latest' : '9-jre11', 'lts' : '9.3-jre8' ]
]

/**
 * Defines the latest versions of supported XWiki configurations. Note that this excludes the default configuration
 * since this one is already executed by the main pipeline job execution.
 *
 * See <a href="https://dev.xwiki.org/xwiki/bin/view/Community/SupportStrategy/">Support Strategy</a>.
 */
def getLatestConfigurations(def xwikiVersion)
{
    def configurations = [
        'MySQL 8.0.x, Tomcat 9.x (Java 11), Chrome': [
            'database' : 'mysql',
            'databaseTag' : VERSIONS.mysql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.latest,
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'MariaDB 10.4.x, Jetty 9.x (Java 11), Firefox': [
            'database' : 'mariadb',
            'databaseTag' : VERSIONS.mariadb.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : VERSIONS.jetty.latest,
            'browser' : 'firefox',
            'verbose' : 'true'
        ],
        'PostgreSQL 12.3.x, Tomcat 9.x (Java 11), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : VERSIONS.postgresql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.latest,
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'Oracle 19.3.0, Jetty 9.x (Java 11), Firefox': [
            'database' : 'oracle',
            'databaseTag' : VERSIONS.oracle.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : VERSIONS.jetty.latest,
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    ]
    return configurations
}

/**
 * Configurations for smoke tests (i.e. only a few tests) on the maximum number of configurations to flush out problems
 * of configurations when XWiki doesn't start or has basic problems. This includes all supported configurations.
 * Note that this excludes the default configuration since this one is already executed by the main pipeline job
 * execution.
 *
 * See <a href="https://dev.xwiki.org/xwiki/bin/view/Community/SupportStrategy/">Support Strategy</a>.
 */
def getAllConfigurations(def xwikiVersion)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.5.x (Java 8), Chrome': [
            'database' : 'mysql',
            'databaseTag' : VERSIONS.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.lts,
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'MariaDB 10.3.x, Tomcat 8.5.x (Java 8), Firefox': [
            'database' : 'mariadb',
            'databaseTag' : VERSIONS.mariadb.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.lts,
            'browser' : 'firefox',
            'verbose' : 'true'
        ],
        'PostgreSQL 11.8.x, Jetty 9.3.x (Java 8), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : VERSIONS.postgresql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : VERSIONS.jetty.lts,
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Special case: verify we still support utf8 for MySQL and at the same time test with latest Tomcat on Java8
        // to potentially discover problem in advance (to add more value since we're doing another config test).
        'MySQL 5.7.x (utf8), Tomcat 9.x (Java 8), Chrome': [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : VERSIONS.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.special,
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Special case for Debian: Debian "stable" for PostgreSQL is still on 11.7.x. At the same time test with
        // latest Tomcat on Java11 to potentially discover problem in advance (to add more value since we're doing
        // another config test).
        // TOD: Remove as soon as Debian upgrades.
        'PostgreSQL 11.7.x, Tomcat 9.x (Java 11), Firefox': [
            'database' : 'postgresql',
            'databaseTag' : VERSIONS.postgresql.debian,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : VERSIONS.tomcat.latest,
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    ]

    return configurations
}

/**
 * Configurations for smoke tests (i.e. only a few tests) on configurations that we'll want to support in the future
 * but that are currently not supported or not working.
 *
 * See <a href="https://dev.xwiki.org/xwiki/bin/view/Community/SupportStrategy/">Support Strategy</a>.
 */
def getUnsupportedConfigurations(def xwikiVersion)
{
    def configurations = [
        // Test on latest MySQL, latest Tomcat, Java LTS
        'MySQL latest, Tomcat latest (Java LTS), Chrome': [
            'database' : 'mysql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : 'latest',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Test on latest PostgreSQL, latest Jetty, Java LTS
        'PostgreSQL latest, Jetty latest (Java LTS), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : 'latest',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Verify XWiki works on the latest released Java version in order to prepare for the next Java LTS (which
        // will be Java 17 in 2021).
        // Also test latest MariaDB at the same time.
        'MariaDB latest, Tomcat latest (Java 14), Firefox': [
            'database' : 'mariadb',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : 'jdk14-openjdk-oracle',
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    ]
    return configurations
}
