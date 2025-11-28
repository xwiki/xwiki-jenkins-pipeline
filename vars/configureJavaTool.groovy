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

def call(config, pom)
{
    def results = [:]
    def javaTool = config.javaTool
    if (!javaTool) {
        if (config.sonar) {
            // Sonar requires Java 17+, and since the "official" Java version we use is now >= Java 17, let's use that.
            javaTool = 'official'
        } else {
            javaTool = getJavaTool(pom)
        }
    }
    // NOTE: The Java tool Needs to be configured in the Jenkins global configuration.
    results.jdk = javaTool
    echoXWiki "JavaTool used: ${results.jdk}"

    // Configure MAVEN_OPTS based on the java version found and whether users have configured the mavenOpts or not
    echoXWiki "Found overridden Maven options: ${config.mavenOpts}"
    def mavenOpts = config.mavenOpts
    if (!mavenOpts) {
        mavenOpts = '-Xmx1920m -Xms256m'
        if (javaTool == 'java7') {
            mavenOpts = "${mavenOpts} -XX:MaxPermSize=512m"
        }
    }
    // Make sure Maven logs timestamps to help debug issues:
    //   -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS -Dorg.slf4j.simpleLogger.showDateTime=true
    if (!mavenOpts.contains('org.slf4j.simpleLogger.dateTimeFormat')) {
        mavenOpts = "${mavenOpts} -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS"
    }
    if (!mavenOpts.contains('org.slf4j.simpleLogger.showDateTime')) {
        mavenOpts = "${mavenOpts} -Dorg.slf4j.simpleLogger.showDateTime=true"
    }
    // Improve retry options in maven to avoid problems in the CI when nexus is not available immediately.
    mavenOpts = "${mavenOpts} -Daether.connector.http.retryHandler.serviceUnavailable=429,500,503,502 -Daether.connector.http.retryHandler.count=10"

    results.mavenOpts = mavenOpts
    return results
}

/**
 * Read the parent pom to try to guess the java tool to use based on the parent pom version.
 * <ul>
 *   <li>XWiki versions < 14 should use Java 8.</li>
 *   <li>XWiki versions >= 14 and < 16 should use Java 11.</li>
 *   <li>XWiki versions >= 16 should use the official java version</li>
 *  </ul>
 */
def getJavaTool(pom)
{
    def parent = pom.parent
    def groupId
    def artifactId
    def version
    if (parent != null) {
        groupId = parent.groupId
        artifactId = parent.artifactId
        version = parent.version
    } else {
        // We're on the top pom (no parent)
        groupId = pom.groupId
        artifactId = pom.artifactId
        version = pom.version

    }
    echoXWiki "GroupID: ${groupId}"
    echoXWiki "ArtifactID: ${artifactId}"
    echoXWiki "Version: ${version}"
    if (isKnownParent(groupId, artifactId)) {
        def major = version.substring(0, version.indexOf('.'))
        echoXWiki "Major version: ${major}"
        if (major.toInteger() < 14) {
            return 'java8'
        } else if (major.toInteger() < 16) {
            return 'java11'
        } else if (major.toInteger() < 18) {
            return 'java17'
        } else if (major.toInteger() < 20) {
            return 'java21'
        } else if (major.toInteger() < 22) {
            return 'java25'
        }
    }
    return 'official'
}

/**
 * Check that the parent is XWiki commons, rendering or platform since we know the java requirements for these
 * modules based on their versions.
 */
def isKnownParent(parentGroupId, parentArtifactId)
{
    return (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-platform') ||
           (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-commons') ||
           (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-rendering') ||
           (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-platform-distribution') ||
           parentGroupId == 'org.xwiki.commons' ||
           parentGroupId == 'org.xwiki.rendering' ||
           parentGroupId == 'org.xwiki.platform'
}
