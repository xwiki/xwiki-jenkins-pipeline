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

void call(config, pom)
{
    def javaTool = config.javaTool
    if (!javaTool) {
        javaTool = getJavaTool(pom)
    }
    // NOTE: The Java tool Needs to be configured in the Jenkins global configuration.
    env.JAVA_HOME="${tool javaTool}"
    echoXWiki "JAVA_HOME: ${env.JAVA_HOME}"
    env.PATH="${env.JAVA_HOME}/bin:${env.PATH}"

    // Configure MAVEN_OPTS based on the java version found and whether users have configured the mavenOpts or not
    echoXWiki "Found overridden Maven options: ${config.mavenOpts}"
    def mavenOpts = config.mavenOpts
    if (!mavenOpts) {
        mavenOpts = '-Xmx1920m -Xms256m'
        if (javaTool == 'java7') {
            mavenOpts = "${mavenOpts} -XX:MaxPermSize=512m"
        }
    }
    // Note: withMaven is concatenating any passed "mavenOpts" with env.MAVEN_OPTS. Thus in order to fully
    // control the maven options used we only set env.MAVEN_OPTS and don't pass "mavenOpts" when using withMaven.
    // See http://bit.ly/2zwl4IU
    env.MAVEN_OPTS = mavenOpts
}

/**
 * Read the parent pom to try to guess the java tool to use based on the parent pom version.
 * XWiki versions < 8 should use Java 7.
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
    if (isKnownParent(groupId, artifactId)) {
        // If version < 8 then use Java7, otherwise official
        def major = version.substring(0, version.indexOf('.'))
        if (major.toInteger() < 8) {
            return 'java7'
        }
    }
    return 'official'
}

/**
 * Since we're trying to guess the Java version to use based on the parent POM version, we need to ensure that the
 * parent POM points to an XWiki core module (there are several possible) so that we can compare with the version 8.
 */
def isKnownParent(parentGroupId, parentArtifactId)
{
    return (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-platform') ||
            (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-commons') ||
            (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-rendering') ||
            (parentGroupId == 'org.xwiki.commons' && parentArtifactId == 'xwiki-commons-pom') ||
            (parentGroupId == 'org.xwiki.rendering' && parentArtifactId == 'xwiki-rendering') ||
            (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform') ||
            (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform-distribution')
}
