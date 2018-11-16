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

// Execute smoke tests on the maximum number of configurations to flush out problems of configurations when XWiki
// doesn't start or has basic problems.
def configurations = [
    'MySQL 5.7.x, Tomcat 8.x, Chrome': [
        'database' : 'mysql',
        'databaseTag' : '5.7',
        'jdbcVersion' : '5.1.45',
        'servletEngine' : 'tomcat',
        'servletEngineTag' : '8',
        'browser' : 'chrome'
    ],
    'MySQL 5.5.x, Tomcat 8.x, Firefox': [
        'database' : 'mysql',
        'databaseTag' : '5.5',
        'jdbcVersion' : '5.1.45',
        'servletEngine' : 'tomcat',
        'servletEngineTag' : '8',
        'browser' : 'chrome'
    ],
    'PostgreSQL 11.x, Jetty 9.x, Chrome': [
        'database' : 'postgresql',
        'databaseTag' : '11',
        'jdbcVersion' : '42.2.5',
        'servletEngine' : 'jetty',
        'servletEngineTag' : '9',
        'browser' : 'chrome'
    ],
    'PostgreSQL 9.4.x, Jetty 9.x, Firefox': [
        'database' : 'postgresql',
        'databaseTag' : '9.4',
        'jdbcVersion' : '42.2.5',
        'servletEngine' : 'jetty',
        'servletEngineTag' : '9',
        'browser' : 'chrome'
    ],
    'PostgreSQL 9.6.x, Jetty 9.x, Chrome': [
        'database' : 'postgresql',
        'databaseTag' : '9.6',
        'jdbcVersion' : '42.2.5',
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

def call()
{
    // Smoke test modules.
    def modules = [
        "xwiki-platform-core/xwiki-platform-menu/xwiki-platform-menu-test"
    ]
    new DockerTestUtils().executeDockerTests(configurations, modules)
}