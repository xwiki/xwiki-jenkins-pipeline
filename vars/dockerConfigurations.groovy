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
    // Note: these version only specify major and minor (and not bugfix) so that:
    // - we always test with the latest bugfix version
    // - we reduce the maintenance (since specifying the bugfix part would mean updating them all the time)
    def versions = [
        'mysql' : [ 'latest' : '8.0', 'lts' : '5.7' ],
        'mariadb' : [ 'latest' : '10.5', 'lts' : '10.4', 'debian' : '10.3' ],
        // Note: for postgreSQL latest is the last cycle and LTS the previous one. Thus, we don't specify the minor to
        // be always up to date in our tests. We also specify the Debian version since it doesn't match latest or LTS.
        'postgresql' : [ 'latest' : '12', 'lts' : '11', 'debian' : '11.7' ],
        'oracle' : [ 'latest' : '19.3.0-se2' ],
        'tomcat' : [ 'latest' : '9-jdk11', 'lts' : '8.5-jdk8', 'special' : '9-jdk8' ],
        'jetty' : [ 'latest' : '9-jre11', 'lts' : '9.3-jre8' ]
    ]

    def configurations = [:]
    configurations.'docker-latest' = getLatestConfigurations(xwikiVersion, versions)
    configurations.'docker-all' = getAllConfigurations(xwikiVersion, versions)
    configurations.'docker-unsupported' = getUnsupportedConfigurations(xwikiVersion, versions)
    return configurations.get(configurationName)
}

/**
 * Defines the latest versions of supported XWiki configurations. Note that this excludes the default configuration
 * since this one is already executed by the main pipeline job execution.
 *
 * See <a href="https://dev.xwiki.org/xwiki/bin/view/Community/SupportStrategy/">Support Strategy</a>.
 */
def getLatestConfigurations(def xwikiVersion, def versions)
{
    def configurations = [
        "MySQL ${versions.mysql.latest}, Tomcat ${versions.tomcat.latest}, Chrome": [
            'database' : 'mysql',
            'databaseTag' : versions.mysql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest,
            'browser' : 'chrome'
        ],
        "MariaDB ${versions.mariadb.latest}, Jetty ${versions.jetty.latest}, Firefox": [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latest,
            'browser' : 'firefox'
        ],
        "PostgreSQL ${versions.postgresql.latest}, Tomcat ${versions.tomcat.latest}, Chrome": [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest,
            'browser' : 'chrome'
        ],
        "Oracle ${versions.oracle.latest}, Jetty ${versions.jetty.latest}, Firefox": [
            'database' : 'oracle',
            'databaseTag' : versions.oracle.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latest,
            'browser' : 'firefox'
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
def getAllConfigurations(def xwikiVersion, def versions)
{
    def configurations = [
        "MySQL ${versions.mysql.lts}, Tomcat ${versions.tomcat.lts}, Chrome": [
            'database' : 'mysql',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts,
            'browser' : 'chrome'
        ],
        "MariaDB ${versions.mariadb.lts}, Tomcat ${versions.tomcat.lts}, Firefox": [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts,
            'browser' : 'firefox'
        ],
        "MariaDB ${versions.mariadb.debian} (Debian), Tomcat ${versions.tomcat.lts}, Firefox": [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.debian,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts,
            'browser' : 'firefox'
        ],
        "PostgreSQL ${versions.postgresql.lts}, Jetty ${versions.jetty.lts}, Chrome": [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.lts,
            'browser' : 'chrome'
        ],
        // Special case: verify we still support utf8 for MySQL and at the same time test with latest Tomcat on Java8
        // to potentially discover problem in advance (to add more value since we're doing another config test).
        "MySQL ${versions.mysql.lts} (utf8), Tomcat ${versions.tomcat.special} (latest on Java 8), Chrome": [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.special,
            'browser' : 'chrome'
        ],
        // Special case for Debian: Debian "stable" for PostgreSQL is still on 11.7.x. At the same time test with
        // latest Tomcat on Java11 to potentially discover problem in advance (to add more value since we're doing
        // another config test).
        // TOD: Remove as soon as Debian upgrades.
        "PostgreSQL ${versions.postgresql.debian} (Debian), Tomcat ${versions.tomcat.latest}, Firefox": [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.debian,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest,
            'browser' : 'firefox'
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
def getUnsupportedConfigurations(def xwikiVersion, def versions)
{
    def configurations = [
        // Test on latest MySQL, latest Tomcat, Java LTS
        'MySQL latest, Tomcat latest (Java LTS), Chrome': [
            'database' : 'mysql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : 'latest',
            'browser' : 'chrome'
        ],
        // Test on latest PostgreSQL, latest Jetty, Java LTS
        'PostgreSQL latest, Jetty latest (Java LTS), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : 'latest',
            'browser' : 'chrome'
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
            'browser' : 'firefox'
        ]
    ]
    return configurations
}
