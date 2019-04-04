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

def call(configurationName, xwikiPlatformBranch)
{
    def configurations = [:]
    configurations.latest = getLatestConfigurations(xwikiPlatformBranch)
    configurations.all = getAllConfigurations(xwikiPlatformBranch)
    configurations.unsupported = getUnsupportedConfigurations(xwikiPlatformBranch)
    return configurations.get(configurationName)
}

/**
 * Defines the latest versions of supported XWiki configurations, according to:
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
 * <li>Ability to have different configurations depending on the branch in the source (for example in XWiki 10.11.x we
 *   don't support running on Java 11.x</li>
 * </ul>
 * Disadvantages:
 * <ul>
 * <li>Ability to paralellize, i.e. execute each Maven build on a different CI agent. This is because the withMaven()
 *   step doesn't currently support this feature (which is a pity). See also
 *   https://massol.myxwiki.org/xwiki/bin/view/Blog/Jenkins%20and%20large%20Maven%20projects<li>
 * </ul>
 * <p>
 *
 * @param xwikiPlatformBranch the branch to build on (e.g. "stable-10.10.x"). If not specified then we assume it's "master"
 */
def getLatestConfigurations(def xwikiPlatformBranch)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.5.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8.5',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 11.x, Jetty 9.2.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9.2',
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
    return configurations
}

/**
 * Configurations for smoke tests (i.e. only a few tests) on the maximum number of configurations to flush out problems
 * of configurations when XWiki doesn't start or has basic problems. This includes all supported configurations.
 *
 * @param xwikiPlatformBranch the branch to build on (e.g. "master", "stable-10.10.x")
 */
def getAllConfigurations(def xwikiPlatformBranch)
{
    def configurations = [
        'MySQL 5.7.x, Tomcat 8.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'MySQL 5.5.x, Tomcat 8.x, Firefox': [
            'database' : 'mysql',
            'databaseTag' : '5.5',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 11.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '11',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 9.4.x, Jetty 9.x, Firefox': [
            'database' : 'postgresql',
            'databaseTag' : '9.4',
            'jdbcVersion' : '42.2.5',
            'servletEngine' : 'jetty',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        'PostgreSQL 9.6.x, Jetty 9.x, Chrome': [
            'database' : 'postgresql',
            'databaseTag' : '9.6',
            'jdbcVersion' : '42.2.5',
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

    // The LTS branch currently doesn't support the following configurations which is why they're only active for the
    // master branch.
    // TODO: Merge with config above when LTS becomes 11.x
    if (!xwikiPlatformBranch || xwikiPlatformBranch == 'master') {
        configurations.'MySQL 5.7.x (utf8mb4), Tomcat 8.x, Chrome' = [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8mb4',
            'database.commands.collation-server' : 'utf8mb4_bin',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ]
        configurations.'MySQL 5.7.x, Tomcat 8.x (Java 11), Firefox' = [
            'database' : 'mysql',
            'databaseTag' : '5.7',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8-jre11',
            'browser' : 'firefox',
            'verbose' : 'true'
        ]
    }
    return configurations
}

/**
 * Coonfigurations for smoke tests (i.e. only a few tests) on configurations that we'll want to support in the future
 * but that are currently not supported or not working.
 *
 * @param xwikiPlatformBranch the branch to build on (e.g. "master", "stable-10.10.x")
 */
def getUnsupportedConfigurations(def xwikiPlatformBranch)
{
    def configurations = [
        // Test on latest MySQL 8.x.
        'MySQL 8.x, Tomcat 8.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '8',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '8',
            'browser' : 'chrome',
            'verbose' : 'true'
        ],
        // Test on latest MySQL 5.x & Tomcat 9.x.
        'MySQL 5.x, Tomcat 9.x, Chrome': [
            'database' : 'mysql',
            'databaseTag' : '5',
            'jdbcVersion' : '5.1.45',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : '9',
            'browser' : 'chrome',
            'verbose' : 'true'
        ]
    ]
    return configurations
}
