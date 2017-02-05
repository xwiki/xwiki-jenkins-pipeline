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

// Example usage:
//   xwikiModule {
//     goals = 'clean install' (default is 'clean deploy')
//     profiles = 'legacy,integration-tests,jetty,hsqldb,firefox' (default is 'quality,legacy,integration-tests')
//     mavenOpts = '-Xmx2048m' (default is '-Xmx1024m')
//     mavenTool = 'Maven 3' (default is 'Maven')
//     javaTool = 'java7' (default is 'official')
//     timeout = 60 (default is 240 minutes)
//  }

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Now build, based on the configuration provided, using the followong configuration:
    // - config.name: the name of the module in git, e.g. "syntax-markdown"

    node {
        def mvnHome
        stage('Preparation') {
            // Get the Maven tool.
            // NOTE: Needs to be configured in the global configuration.
            def mavenTool = config.mavenTool ?: 'Maven'
            mvnHome = tool mavenTool
            echoXWiki "Using Maven: ${mvnHome}"
        }
        stage('Build') {
            checkout scm
            // Configure the version of Java to use
            configureJavaTool(config)
            // Execute the XVNC plugin (useful for integration-tests)
            wrap([$class: 'Xvnc']) {
                def mavenOpts = config.mavenOpts ?: '-Xmx1024m'
                echoXWiki "Using Maven options: ${mavenOpts}"
                withEnv(["PATH+MAVEN=${mvnHome}/bin", "MAVEN_OPTS=${mavenOpts}"]) {
                    try {
                        def goals = config.goals ?: 'clean deploy'
                        echoXWiki "Using Maven goals: ${goals}"
                        def profiles = config.profiles ?: 'quality,legacy,integration-tests'
                        echoXWiki "Using Maven profiles: ${profiles}"
                        def timeoutThreshold = config.timeout ?: 240
                        echoXWiki "Using timeout: ${timeoutThreshold}"
                        // Abort the build if it takes more than the timeout threshold (in minutes).
                        timeout(timeoutThreshold) {
                            sh "mvn ${goals} jacoco:report -P${profiles} -U -e -Dmaven.test.failure.ignore"
                        }
                        currentBuild.result = 'SUCCESS'
                    } catch (Exception err) {
                        currentBuild.result = 'FAILURE'
                        notifyByMail(currentBuild.result)
                        throw e
                    }
                }
            }
        }
        stage('Post Build') {
            // Save the JUnit test report
            junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
        }
    }
}

def notifyByMail(String buildStatus) {
    buildStatus =  buildStatus ?: 'SUCCESSFUL'
    def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def details = """<p>STARTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>"""

    def to = emailextrecipients([
            [$class: 'CulpritsRecipientProvider'],
            [$class: 'DevelopersRecipientProvider'],
            [$class: 'RequesterRecipientProvider']
    ])
    if (to != null && !to.isEmpty()) {
        mail to: to, subject: subject, body: details
    }
}

def echoXWiki(string) {
    echo "\u27A1 ${string}"
}

def configureJavaTool(config) {
    // Configure which Java version to use by Maven. If not specified try to guess it depending on the
    // parent pom version.
    def javaTool = config.javaTool
    if (!javaTool) {
        javaTool = getJavaTool()
    }
    env.JAVA_HOME="${tool javaTool}"
    env.PATH="${env.JAVA_HOME}/bin:${env.PATH}"
    echoXWiki "Using Java: ${env.JAVA_HOME}"
}

def getJavaTool() {
    def pom = readMavenPom file: 'pom.xml'
    def parent = pom.parent
    def parentGroupId = parent.groupId
    def parentArtifactId = parent.artifactId
    def parentVersion = parent.version
    if (isKnownParent(parentGroupId, parentArtifactId)) {
        // If version < 8 then use Java7, otherwise official
        def major = parentVersion.substring(0, parentVersion.indexOf('.'))
        if (major.toInteger() < 8) {
            return "java7"
        }
    }
    return 'official'
}

def boolean isKnownParent(parentGroupId, parentArtifactId) {
    return (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-platform') ||
        (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-commons') || 
        (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-rendering') || 
        (parentGroupId == 'org.xwiki.commons' && parentArtifactId == 'xwiki-commons-pom') ||
        (parentGroupId == 'org.xwiki.rendering' && parentArtifactId == 'xwiki-rendering') ||
        (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform') ||
        (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform-distribution')
}
