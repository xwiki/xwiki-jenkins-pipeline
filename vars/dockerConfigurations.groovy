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
        'mysql' : [ 'latest' : '9.1', 'lts' : '8.4' ],
        'mariadb' : [ 'latest' : '11.5', 'lts' : '11.4' ],
        // Note: for postgreSQL latest is the last cycle and LTS the previous one. Thus, we don't specify the minor to
        // be always up to date in our tests.
        'postgresql' : [ 'latest' : '17', 'lts' : '16' ],
        'oracle' : [ 'latest' : '19.3.0-se2' ],
        // Notes:
        // - We cannot use Tomcat 10.x right now as the latest version since that corresponds to a package change
        //   for JakartaEE and we'll need XWiki to move to the new packages first. This is why LTS = latest FTM.
        // - We need to support both Java 17 and Java 21 so we map latest to Java 21 and LTS to java 17 to test both.
        'tomcat' : [ 'latest' : '9-jdk21', 'lts' : '9-jdk17', 'special' : '9-jdk11'],
        // Notes:
        // - Starting with Jetty 12, Jetty supports running an EE8 environment (i.e. "javax.servlet") which allows us
        //   to run XWiki on it. This is not supported in Jetty 11.
        // - We need to support both Java 17 and Java 21 so we map latest to Java 21 and LTS to java 17 to test both.
        // - Non-master branches currently don't support executing with Jetty 12 and thus we use Jetty 10 on the older
        //   branches.
        'jetty' : [ 'latestmaster' : '12-jdk21', 'latest' : '10-jdk21', 'lts' : '10-jdk17' ]
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
    def jettyLatest = versions.jetty.latest
    if (isXWikiVersionGreaterThan(xwikiVersion, '16', '7')) {
        jettyLatest = versions.jetty.latestmaster
    }

    def configurations = [
        "MySQL ${versions.mysql.latest}, Tomcat ${versions.tomcat.latest}, Chrome": [
            'database' : 'mysql',
            'databaseTag' : versions.mysql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest,
            'browser' : 'chrome'
        ],
        "MariaDB ${versions.mariadb.latest}, Jetty ${jettyLatest}, Firefox": [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : jettyLatest,
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
        "Oracle ${versions.oracle.latest}, Jetty ${jettyLatest}, Firefox": [
            'database' : 'oracle',
            'databaseTag' : versions.oracle.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : jettyLatest,
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
        ]
    ]

    // Verify MySQL LTS on Tomcat LTS and at the same time we verify that we still support utf8 for MySQL.
    // Note 1: MySQL on utmb4 is tested in latest configurations.
    // Note 2: We run these tests on Tomcat/Java 11 for XWiki 15.x to make sure we still support Java 11.
    def tomcatVersion = versions.tomcat.lts
    if (xwikiVersion.startsWith("15.")) {
        tomcatVersion = versions.tomcat.special
    }
    configurations.putAll([
        "MySQL ${versions.mysql.lts} (utf8), Tomcat ${tomcatVersion}, Chrome": [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : tomcatVersion,
            'browser' : 'chrome'
        ]
    ])

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
        'MySQL latest, Tomcat latest 9.x (Java LTS), Chrome': [
            'database' : 'mysql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            // Note: we cannot use 10.x right now since that corresponds to a package change for JakartaEE and we'll
            // need XWiki to move to the new packages first.
            'servletEngineTag' : '9-jdk21',
            'browser' : 'chrome'
        ],
        // Test on latest PostgreSQL, latest Jetty, Java LTS
        'PostgreSQL latest, Jetty latest 10.x (Java LTS), Chrome': [
            'database' : 'postgresql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            // Note: we cannot use 11.x right now since that corresponds to a package change for JakartaEE and we'll
            // need XWiki to move to the new packages first.
            'servletEngineTag' : '10-jdk21',
            'browser' : 'chrome'
        ],
        // Test on latest MariaDB, Tomcat LTS, latest Java
        'MariaDB latest, Tomcat latest 9.x (Java 17), Firefox': [
            'database' : 'mariadb',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9-jdk21',
            'browser' : 'firefox'
        ]
    ]
    return configurations
}

private def isXWikiVersionGreaterThan(xwikiVersion, major, minor)
{
    def result
    if (xwikiVersion) {
        def versionParts = xwikiVersion?.split('\\.')
        if (versionParts[0] >= major && versionParts[1] >= minor) {
            result = true
        } else {
            result = false
        }
    } else {
        result = true
    }
    return result
}
