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

def call()
{
    // Old list of false positives messages. Commented-out for now to start afresh since we haven't seen them for a long
    // time and it takes time to parse the logs for them.
    /*
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
         "Error connecting to FF browser"],
        ["process apparently never started in.*", "Jenkins error", ""]
    ]
    */

    def falsePositiveMessages = []

    // For the moment, only run this test on master until we're sure it works fine
    def branchName = env['BRANCH_NAME']
    echoXWiki "False positives - Branch: [${branchName}]"
    if (branchName != null && isMasterBranch(branchName)) {
        // Format is:
        // - first element: the pattern to recognize the false positive in the logs.
        // - second element: the text used in the badge and on the job summary page
        def messages = [
            [".*Error setting up the XWiki testing environment on agent.*", "Docker test setup issue"],
            [".*Java heap space.*", "Memory issue"]
        ]
        messages.each { message ->
            echoXWiki "False positive - Looking for message: [${message.get(0)}]"
            if (manager.logContains(message.get(0))) {
                echoXWiki "False positive detected [${message.get(1)}] ..."
                falsePositiveMessages.add(message)
            }
        }
        if (falsePositiveMessages) {
            echoXWiki "False positives found!"
            // Display the badges
            falsePositiveMessages.each() { message ->
                // Only add the badge once since this code can be called several times (e.g. we run several builds, one
                // for each tested environment).
                def badgeText = message.get(1)
                def badgeFound = isBadgeFound(badgeText)
                if (!badgeFound) {
                    manager.addWarningBadge(badgeText)
                }
            }
            // Display the info on the job results page
            // Replace the existing summary with the accrued list of false positives found
            manager.removeSummaries()
            def summary = manager.createSummary("warning.gif")
            summary.appendText("False positives found<ul>", false, false, false, 'red')
            falsePositiveMessages.each() { message ->
                summary.appendText("<li>${message.get(1)}</li>", false, false, false, 'red')
            }
            summary.appendText("</ul>", false, false, false, 'red')
            // Persist badge changes
            saveCurrentBuildChanges()
        }
    }

    return !falsePositiveMessages.isEmpty()
}


/*
private def logContains(regexp)
{
    return contains(currentBuild.rawBuild.getLogReader(), regexp)
}

private def contains(reader, regexp)
{
    def matcher = getMatcher(reader, regexp)
    return (matcher != null) && matcher.matches()
}

private def getMatcher(reader, regexp)
{
    def matcher = null
    new BufferedReader(reader).with { br ->
        def pattern = Pattern.compile(regexp)
        def line = null
        line = br.readLine()
        while (line != null) {
            def m = pattern.matcher(line);
            if (m.matches()) {
                matcher = m
                break
            }
            line = br.readLine()
        }
    }
    return matcher
}
*/
