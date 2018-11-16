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

return this