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

// Execute all  docker functional tests on all supported configurations, according to:
// - DBs: https://dev.xwiki.org/xwiki/bin/view/Community/DatabaseSupportStrategy
// - Servlet containers: https://dev.xwiki.org/xwiki/bin/view/Community/ServletContainerSupportStrategy/
// - Browsers: https://dev.xwiki.org/xwiki/bin/view/Community/BrowserSupportStrategy
// Note that for browsers we're constrained to use the version of them supported by the Selenium version we use. Our
// strategy is to always use the latest released Selenium version in order to use the latest browser versions.
//
// TODO: In the future replace this by Java code located in xwiki-platform-test-docker when JUnit5 supports this.
// (see https://github.com/junit-team/junit5/issues/871).
// It'll bring the following advantages:
// - Less reliance on the CI. If we need to get away from Jenkins for ex, it'll make it easier. In general we need to
//   have the maximum done in the Maven build and the minimum in CI scripts.
// - Ability to run several configs at once on developer's machines.
// - Ability to have a single Maven build executed in the CI and thus not get tons of mails whenever a test fails
//   (which is the current situation).
// Disadvantages:
// - Ability to paralellize, i.e. execute each Maven build on a different CI agent. This is because the withMaven() step
//   doesn't currently support this feature (which is a pity).
//   See also https://massol.myxwiki.org/xwiki/bin/view/Blog/Jenkins%20and%20large%20Maven%20projects
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

def executeDockerTests()
{
    new DockerTestUtils().executeDockerTests(configurations, null)
}
