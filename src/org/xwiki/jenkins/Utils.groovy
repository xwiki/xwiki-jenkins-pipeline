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
package org.xwiki.jenkins

// Example usage:
//   import org.xwiki.jenkins.Utils
//   new Utils().echoXWiki("test")

/**
 * Echo text with a special character prefix to make it stand out in the pipeline logs.
 */
def echoXWiki(string)
{
    echo "\u27A1 ${string}"
}

def notifyByMail(String buildStatus, String name)
{
    echoXWiki "Build has failed, sending mails to concerned parties"

    // Sending mails to:
    // - culprits: list of users who committed a change since the last non-broken build till now
    // - developers: anyone who checked in code for the last build
    // - requester: whoever triggered the build manually

    // TODO: Sending some simple mail content FTM since it seems that parsing large logs fails and hangs the job.
    sendSimpleMail(buildStatus, name)
}

def sendFullMail(String buildStatus, String name)
{
    emailext (
        subject: "${env.JOB_NAME} - [${name}] - Build # ${env.BUILD_NUMBER} - ${buildStatus}",
        body: '''
Check console output at $BUILD_URL to view the results.

Failed tests:

${FAILED_TESTS}

Cause of error:

${BUILD_LOG_REGEX, regex = ".*BUILD FAILURE.*", linesBefore = 250, linesAfter = 0}

Maven error reported:

${BUILD_LOG_REGEX, regex = ".*Re-run Maven using the -X switch to enable full debug logging*", linesBefore = 100, linesAfter = 0}
''',
        mimeType: 'text/plain',
        recipientProviders: [
            [$class: 'CulpritsRecipientProvider'],
            [$class: 'DevelopersRecipientProvider'],
            [$class: 'RequesterRecipientProvider']
        ]
    )
}

def sendSimpleMail(String buildStatus, String name)
{
    emailext (
        subject: "${env.JOB_NAME} - [${name}] - Build # ${env.BUILD_NUMBER} - ${buildStatus}",
        body: '''
Check console output at $BUILD_URL to view the results.

Failed tests:

${FAILED_TESTS}
''',
        mimeType: 'text/plain',
        recipientProviders: [
            [$class: 'CulpritsRecipientProvider'],
            [$class: 'DevelopersRecipientProvider'],
            [$class: 'RequesterRecipientProvider']
        ]
    )
}

/**
 * Check for false positives for known cases of failures not related to code.
 *
 * @return true if false positives have been detected
 */
def checkForFalsePositives()
{
    def messages = [
        [".*A fatal error has been detected by the Java Runtime Environment.*", "JVM Crash", "A JVM crash happened!"],
        [".*Error: cannot open display: :1.0.*", "VNC not running", "VNC connection issue!"],
        [".*java.lang.NoClassDefFoundError: Could not initialize class sun.awt.X11GraphicsEnvironment.*", "VNC issue",
             "VNC connection issue!"],
        [".*hudson.plugins.git.GitException: Could not fetch from any repository.*", "Git issue",
             "Git fetching issue!"],
        [".*Error communicating with the remote browser. It may have died..*", "Browser issue",
             "Connection to Browser has died!"],
        [".*Failed to start XWiki in .* seconds.*", "XWiki Start", "Failed to start XWiki fast enough!"],
        [".*Failed to transfer file.*nexus.*Return code is:.*ReasonPhrase:Service Temporarily Unavailable.",
             "Nexus down", "Nexus is down!"],
        [".*com.jcraft.jsch.JSchException: java.net.UnknownHostException: maven.xwiki.org.*",
             "maven.xwiki.org unavailable", "maven.xwiki.org is not reachable!"],
        [".*Fatal Error: Unable to find package java.lang in classpath or bootclasspath.*", "Compilation issue",
             "Compilation issue!"],
        [".*hudson.plugins.git.GitException: Command.*", "Git issue", "Git issue!"],
        [".*Caused by: org.openqa.selenium.WebDriverException: Failed to connect to binary FirefoxBinary.*",
             "Browser issue", "Browser setup is wrong somehow!"],
        [".*java.net.SocketTimeoutException: Read timed out.*", "Unknown connection issue",
             "Unknown connection issue!"],
        [".*Can't connect to X11 window server.*", "VNC not running", "VNC connection issue!"],
        [".*The forked VM terminated without saying properly goodbye.*", "Surefire Forked VM crash",
             "Surefire Forked VM issue!"],
        [".*java.lang.RuntimeException: Unexpected exception deserializing from disk cache.*", "GWT building issue",
             "GWT building issue!"],
        [".*Unable to bind to locking port 7054.*", "Selenium bind issue with browser", "Selenium issue!"],
        [".*Error binding monitor port 8079: java.net.BindException: Cannot assign requested address.*",
             "XWiki instance already running", "XWiki stop issue!"],
        [".*Caused by: java.util.zip.ZipException: invalid block type.*", "Maven build error",
             "Maven generated some invalid Zip file"],
        [".*java.lang.ClassNotFoundException: org.jvnet.hudson.maven3.listeners.MavenProjectInfo.*", "Jenkins error",
             "Unknown Jenkins error!"],
        [".*Failed to execute goal org.codehaus.mojo:sonar-maven-plugin.*No route to host.*", "Sonar error",
             "Error connecting to Sonar!"],
        ["org\\.openqa\\.selenium\\.firefox\\.NotConnectedException: Unable to connect to host .*.*", "Selenium error",
            "Error connecting to FF browser"]
    ]

    def hasFalsePositives = false
    messages.each { message ->
        if (manager.logContains(message.get(0))) {
            manager.addWarningBadge(message.get(1))
            manager.createSummary("warning.gif").appendText("<h1>${message.get(2)}</h1>", false, false, false, "red")
            manager.buildUnstable()
            echoXWiki "False positive detected [${message.get(2)}] ..."
            hasFalsePositives = true
        }
    }

    return hasFalsePositives
}

return this