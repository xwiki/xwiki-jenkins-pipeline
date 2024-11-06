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
        'oracle' : [ 'latest' : '19.3.0-se2' ]
    ]

    // Java versions
    def javaMaxVersion = 21;
    def javaMinVersion;
    def major = xwikiVersion.substring(0, xwikiVersion.indexOf('.'))
    if (major.toInteger() < 16) {
        javaMinVersion = 11
    } else {
        javaMinVersion = 17
    }

    // Application servers (Tomcat and Jetty) versions
    def tomcatUnsupportedVersion = 'latest';
    def tomcatMaxVersion = 11;
    def tomcatMinVersion = 10;
    def jettyUnsupportedVersion = 'latest';
    def jettyMaxVersion = 12;
    def jettyMinVersion = 11;
    // javax based branches of XWiki cannot always use the latest versions of Tomcat and Jetty
    // TODO: change for something like isXWikiVersionAtLeast(xwikiVersion, '17', '0') when the jakarta branch is merged
    if (!xwikiVersion.contains('feature-deploy-jakarta')) {
        tomcatMaxVersion = 9
        tomcatMinVersion = 9
        tomcatUnsupportedVersion = tomcatMaxVersion

        jettyMinVersion = 10
        // - Starting with Jetty 12, Jetty supports running an EE8 environment (i.e. "javax.servlet") which allows us
        //   to run XWiki on it.
        // - Except for versions of Xwiki lower than 16.7 for which we are stuck with Jetty 10
        if (!isXWikiVersionAtLeast(xwikiVersion, '16', '7')) {
            jettyMaxVersion = 10
            jettyUnsupportedVersion = jettyMaxVersion
        }
    }

    versions.'tomcat' = [ 'latest' : "${tomcatMaxVersion}-jdk${javaMaxVersion}", 'lts' : "${tomcatMinVersion}-jdk${javaMinVersion}", 'latestunsupported' : tomcatUnsupportedVersion]
    versions.'jetty' = [ 'latest' : "${jettyMaxVersion}-jdk${javaMaxVersion}", 'lts' : "${jettyMinVersion}-jdk${javaMinVersion}", 'latestunsupported' : jettyUnsupportedVersion ]

    def configurations = [:]
    configurations.'docker-latest' = getLatestConfigurations(versions)
    configurations.'docker-all' = getAllConfigurations(versions)
    configurations.'docker-unsupported' = getUnsupportedConfigurations(versions)
    return configurations.get(configurationName)
}

/**
 * Defines the latest versions of supported XWiki configurations. Note that this excludes the default configuration
 * since this one is already executed by the main pipeline job execution.
 *
 * See <a href="https://dev.xwiki.org/xwiki/bin/view/Community/SupportStrategy/">Support Strategy</a>.
 */
def getLatestConfigurations(def versions)
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
def getAllConfigurations(def versions)
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
        ],
        // Also make sure XWiki keep working with utf8 on MySQL (utf8mb4 is tested in latest configurations)
        "MySQL ${versions.mysql.lts} (utf8), Tomcat ${versions.tomcat.lts}, Chrome": [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts,
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
        "MySQL latest, Tomcat ${versions.tomcat.latestunsupported}, Chrome": [
            'database' : 'mysql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latestunsupported,
            'browser' : 'chrome'
        ],
        // Test on latest PostgreSQL, latest Jetty, Java LTS
        "PostgreSQL latest, Jetty ${versions.jetty.latestunsupported}, Chrome": [
            'database' : 'postgresql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latestunsupported,
            'browser' : 'chrome'
        ],
        // Test on latest MariaDB, Tomcat latest, latest Java
        "MariaDB latest, Tomcat ${versions.tomcat.latestunsupported}, Firefox": [
            'database' : 'mariadb',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latestunsupported,
            'browser' : 'firefox'
        ]
    ]
    return configurations
}

private def isXWikiVersionAtLeast(xwikiVersion, major, minor)
{
    def result
    if (xwikiVersion) {
        def versionParts = xwikiVersion?.split('\\.')
        if (versionParts[0] > major || (versionParts[0] == major && versionParts[1] >= minor)) {
            result = true
        } else {
            result = false
        }
    } else {
        result = true
    }
    return result
}
