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
import java.lang.module.ModuleDescriptor.Version

/**
 * Assume that the current directory contains a pom.xml from which we'll extract the version.
 */
def call(configurationName)
{
    // Gather the XWiki version
    def pom = readMavenPom file: 'pom.xml'
    def xwikiVersion = pom.version

    // Note: we use labels as generic as possible to test on latest bugfix versions and reduce maintenance (no need to constantly update this list)

    // Database versions
    def versions = [
        // FIXME: put back 'latest' when all supported branches use testcontainer 2+ (so when 17.10.2 become the LTS)
        'mysql' : [ 'latest' : '9.2', 'lts' : 'lts' ],
        'mariadb' : [ 'latest' : 'latest', 'lts' : 'lts' ],
        // postgresql images don't have the concept of 'lts', we are considering the previous major version to serve this purpose
        'postgresql' : [ 'latest' : 'latest', 'lts' : '17' ],
        // TODO: Find a more recent version of Oracle
        'oracle' : [ 'latest' : '19.3.0-se2' ]
    ]

    // Java versions
    def javaMinVersion = sh script: "mvn -N help:evaluate -Dexpression=xwiki.java.version -q -DforceStdout", returnStdout: true
    echoXWiki "Value of the xwiki.java.version property: ${javaMinVersion}"
    def javaMaxVersion = sh script: "mvn -N help:evaluate -Dexpression=xwiki.java.version.support -q -DforceStdout", returnStdout: true
    echoXWiki "Value of the xwiki.java.version.support property: ${javaMaxVersion}"
    if (javaMinVersion.isNumber() && javaMaxVersion.isNumber()) {
        // The minimum/maximum Java version are indicated in the effective pom
        javaMinVersion = javaMinVersion.toInteger()
        javaMaxVersion = javaMaxVersion.toInteger()
    } else {
        // The minimum/maximum Java versions are not indicated in the effective pom, try to deduce it from the XWiki version
        def major = xwikiVersion.substring(0, xwikiVersion.indexOf('.'))
        if (major.toInteger() < 16) {
            javaMinVersion = 11
            javaMaxVersion = 17
        } else if (major.toInteger() < 18) {
            javaMinVersion = 17
            javaMaxVersion = (isXWikiVersionAtLeast(xwikiVersion, '17.10')) ? 25 : 21
        } else if (major.toInteger() < 20) {
            javaMinVersion = 21
            javaMaxVersion = 25
        } else {
            javaMinVersion = 25
            javaMaxVersion = 25
        }
    }

    // Application servers (Tomcat and Jetty) versions
    def tomcatUnsupportedVersion = "latest";
    def tomcatMaxVersion = 11;
    def tomcatMinVersion = 10;
    def jettyUnsupportedVersion = "latest";
    def jettyMaxVersion = 12;
    def jettyMinVersion = 12.0;
    // javax.servlet based versions of XWiki cannot use the current version of Tomcat
    if (!isXWikiVersionAtLeast(xwikiVersion, '17.0')) {
        tomcatMaxVersion = 9
        tomcatMinVersion = 9
        tomcatUnsupportedVersion = tomcatMaxVersion
    }

    tomcatJavaMaxVersion=javaMaxVersion
    jettyJavaMaxVersion=javaMaxVersion

    versions.'tomcat' = [ 'latest' : "${tomcatMaxVersion}-jdk${tomcatJavaMaxVersion}", 'lts' : "${tomcatMinVersion}-jdk${javaMinVersion}", 'latestunsupported' : "${tomcatUnsupportedVersion}"]
    versions.'jetty' = [ 'latest' : "${jettyMaxVersion}-jdk${jettyJavaMaxVersion}", 'lts' : "${jettyMinVersion}-jdk${javaMinVersion}", 'latestunsupported' : "${jettyUnsupportedVersion}"]

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
    def configurations = [:]

    addConfiguration(
        configurations,
        "MySQL ${versions.mysql.latest}",
        "Tomcat ${versions.tomcat.latest}",
        [
            'database' : 'mysql',
            'databaseTag' : versions.mysql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest
        ], 'filesystem', 'chrome', xwikiVersion
    )

    addConfiguration(
        configurations,
        "MariaDB ${versions.mariadb.latest}",
        "Jetty ${versions.jetty.latest}",
        [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latest
        ], 'filesystem', 'firefox', xwikiVersion
    )

    addConfiguration(
        configurations,
        "PostgreSQL ${versions.postgresql.latest}",
        "Tomcat ${versions.tomcat.latest}",
        [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latest
        ], 's3', 'chrome', xwikiVersion
    )

    addConfiguration(
        configurations,
        "Oracle ${versions.oracle.latest}",
        "Jetty ${versions.jetty.latest}",
        [
            'database' : 'oracle',
            'databaseTag' : versions.oracle.latest,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latest
        ], 's3', 'firefox', xwikiVersion
    )

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
    def configurations = [:]

    addConfiguration(
        configurations,
        "MariaDB ${versions.mariadb.lts}",
        "Tomcat ${versions.tomcat.lts}",
        [
            'database' : 'mariadb',
            'databaseTag' : versions.mariadb.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts
        ], 'filesystem', 'firefox', xwikiVersion
    )

    addConfiguration(
        configurations,
        "PostgreSQL ${versions.postgresql.lts}",
        "Jetty ${versions.jetty.lts}",
        [
            'database' : 'postgresql',
            'databaseTag' : versions.postgresql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.lts
        ], 's3', 'chrome', xwikiVersion
    )

    // Also make sure XWiki keep working with utf8 on MySQL (utf8mb4 is tested in latest configurations)
    addConfiguration(
        configurations,
        "MySQL ${versions.mysql.lts} (utf8)",
        "Tomcat ${versions.tomcat.lts}",
        [
            'database' : 'mysql',
            'database.commands.character-set-server' : 'utf8',
            'database.commands.collation-server' : 'utf8_bin',
            'databaseTag' : versions.mysql.lts,
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.lts
        ], 'filesystem', 'chrome', xwikiVersion
    )

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
    def configurations = [:]

    // Test on latest MySQL, latest Tomcat, Java LTS
    addConfiguration(
        configurations,
        'MySQL latest',
        "Tomcat ${versions.tomcat.latestunsupported}",
        [
            'database' : 'mysql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latestunsupported
        ], 'filesystem', 'chrome', xwikiVersion
    )

    // Test on latest PostgreSQL, latest Jetty, Java LTS
    addConfiguration(
        configurations,
        'PostgreSQL latest',
        "Jetty ${versions.jetty.latestunsupported}",
        [
            'database' : 'postgresql',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'jetty',
            'servletEngineTag' : versions.jetty.latestunsupported
        ], 'filesystem', 'chrome', xwikiVersion
    )

    // Test on latest MariaDB, Tomcat latest, latest Java
    addConfiguration(
        configurations,
        'MariaDB latest',
        "Tomcat ${versions.tomcat.latestunsupported}",
        [
            'database' : 'mariadb',
            'databaseTag' : 'latest',
            'jdbcVersion' : 'pom',
            'servletEngine' : 'tomcat',
            'servletEngineTag' : versions.tomcat.latestunsupported
        ], 's3', 'firefox', xwikiVersion
    )

    return configurations
}

/**
 * Adds a configuration to the given map of configurations.
 *
 * @param configurations the map of configurations to add the new configuration to
 * @param databaseName the name of the database
 * @param servletEngineName the name of the servlet engine
 * @param configuration the map of configuration properties except for the blob store and browser which are added
 * automatically
 * @param blobStore the blob store to use
 * @param browser the browser to use
 * @param xwikiVersion the XWiki version
 */
private static def addConfiguration(def configurations, def databaseName, def servletEngineName, def configuration,
    def blobStore, def browser, def xwikiVersion)
{
    def configurationName
    configuration['browser'] = browser

    // Blob store is only supported starting with XWiki 17.10.
    if (isXWikiVersionAtLeast(xwikiVersion, '17.10')) {
        configurationName = "${databaseName}, ${servletEngineName}, ${blobStore.capitalize()}, ${browser.capitalize()}"
        configuration['blobStore'] = blobStore
    } else {
        configurationName = "${databaseName}, ${servletEngineName}, ${browser.capitalize()}"
    }
    configurations.put(configurationName, configuration)
}

private static def isXWikiVersionAtLeast(String version1, String version2)
{
    def v1 = Version.parse(version1)
    def v2 = Version.parse(version2)
    return v1.compareTo(v2) >= 0
}
