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
        'mysql' : [ 'latest' : '8', 'lts' : '5' ],
        'mariadb' : [ 'latest' : '10.5', 'lts' : '10.4' ],
        // Note: for postgreSQL latest is the last cycle and LTS the previous one. Thus, we don't specify the minor to
        // be always up to date in our tests.
        'postgresql' : [ 'latest' : '13', 'lts' : '12' ],
        'oracle' : [ 'latest' : '19.3.0-se2' ],
        // Note : we cannot use Tomcat 10.x right now as the latest version since that corresponds to a package change
        // for JakartaEE and we'll need XWiki to move to the new packages first. This is why LTS = latest FTM.
        'tomcat' : [ 'latest' : '9', 'lts' : '9', 'special' : '9-jdk8' ],
        // Note : we cannot use Jetty 11.x right now as the latest version since that corresponds to a package change
        // for JakartaEE and we'll need XWiki to move to the new packages first.
        'jetty' : [ 'latest' : '10.0.0-jre11', 'lts' : '9-jre11' ]
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
        ]/*,
        "Oracle ${versions.oracle.latest}, Jetty ${versions.jetty.latest}, Firefox": [
            'database' : 'oracle',
            'databaseTag' : versions.oracle.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latest,
            'browser' : 'firefox'
        ]*/
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
        "PostgreSQL ${versions.postgresql.lts}, Jetty ${versions.jetty.lts}, Chrome": [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.lts,
            'browser' : 'chrome'
        ],
        // Special case: verify we still support utf8 for MySQL and at the same time test with latest Tomcat on Java8
        // (to verify XWiki still works on java 8).
        "MySQL ${versions.mysql.lts} (utf8), Tomcat ${versions.tomcat.special} (latest on Java 8), Chrome": [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.special,
            'browser' : 'chrome'
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
            // Note 1: we should use 'latest' but currently 'latest' is 9.4.x for jetty on dockerhub, see
            // https://hub.docker.com/_/jetty. Put back once latest is 10.x.
            // Note 2: we cannot use 11.x right now since that corresponds to a package change for JakartaEE and we'll
            // need XWiki to move to the new packages first.
            'servletEngineTag' : '10.0.0-jre11',
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
