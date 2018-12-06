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

/**
 * Computes the full Clover TPC for the XWiki project, taking into account all tests located in various repos:
 * xwiki-commons, xwiki-rendering and xwiki-platform.
 * <p>
 * Also performs an analysis of the report by comparing it to a previous report and generating an email
 * if some modules have a global TPC contribution lower than before.
 * <p>
 * Example usage:
 * <code><pre>
 *   import org.xwiki.jenkins.Clover
 *   node('docker') {
 *     new Clover().generateGlobalCoverage()
 *   }
 * </pre></code>
 */
void generateGlobalCoverage()
{
    def mvnHome
    def localRepository
    def cloverDir
    def shortDateString = new Date().format("yyyyMMdd")
    def dateString = new Date().format("yyyyMMdd-HHmm")
    def workspace = pwd()

    stage('Preparation') {
        localRepository = "${workspace}/maven-repository"
        // Make sure that the special Maven local repository exists
        sh "mkdir -p ${localRepository}"
        // Remove all XWiki artifacts from it
        sh "rm -Rf ${localRepository}/org/xwiki"
        sh "rm -Rf ${localRepository}/com/xpn"
        // Make sure that the directory where clover will store its data exists in
        // the workspace and that it's clean
        cloverDir = "${workspace}/clover-data"
        sh "rm -Rf ${cloverDir}"
        sh "mkdir -p ${cloverDir}"
        // Get the Maven tool.
        // NOTE: Needs to be configured in the global configuration.
        mvnHome = tool 'Maven'
    }
    ["xwiki-commons", "xwiki-rendering", "xwiki-platform"].each() { repoName ->
        stage("Clover for ${repoName}") {
            dir (repoName) {
                git "https://github.com/xwiki/${repoName}.git"
                runCloverAndGenerateReport(mvnHome, localRepository, cloverDir)
            }
        }
    }
    stage("Analyze Results") {
        // Find the Clover report to compare with. We compare against the latest version in which all modules have
        // a higher TPC than before.
        def latestReport = getLatestReport()
        def (date, time) = latestReport.tokenize('-')

        // Location of the new clover XML file from the file system.
        def cloverReportDirectory = "${workspace}/xwiki-platform/target/site/clover"
        def cloverXMLReport = "${cloverReportDirectory}/clover.xml"

        // Generate the Diff Report using Maven
        def oldReport =
            "http://maven.xwiki.org/site/clover/${date}/clover-commons+rendering+platform-${latestReport}/clover.xml"
        def reportProperties = getSystemPropertiesAsString([
            'oldCloverXMLReport' : oldReport,
            'oldReportId' : latestReport,
            'newCloverXMLReport' : cloverXMLReport,
            'newReportId' : dateString,
            'diffReportOutputDirectory': cloverReportDirectory
        ])
        dir ("xwiki-platform") {
            withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx2048m']) {
                sh "nice -n 5 mvn -N org.xwiki.clover:xwiki-clover-maven:0.9:report ${reportProperties}"
            }
        }

        // Publish the report remotely
        sh "ssh maven@maven.xwiki.org mkdir -p public_html/site/clover/${shortDateString}"
        def diffHTMLReportName = "XWikiReport-${latestReport}-${dateString}.html"
        def diffHTMLReport = "${cloverReportDirectory}/${diffHTMLReportName}"
        def targetCloverDir = "maven@maven.xwiki.org:public_html/site/clover"
        def targetFile = "${targetCloverDir}/${shortDateString}/${diffHTMLReportName}"
        sh "scp ${diffHTMLReport} ${targetFile}"

        // Find if there are failures in the Diff Report and if so, send the HTML by email
        def htmlContent = readFile diffHTMLReport
        if (htmlContent.contains('FAILURE')) {
            // Send the mail to notify about failures
            sendMail(htmlContent)
        } else {
            // Update the latest.txt file
            writeFile file: "${cloverReportDirectory}/latest.txt", text: "${dateString}"
            sh "scp ${cloverReportDirectory}/latest.txt maven@maven.xwiki.org:public_html/site/clover/latest.txt"
        }
    }
    // Note 1: We run this stage after the Analyze results stage so that we can have the custom XWiki report even if
    // the publishing of clover reports fail (The custom XWiki report is the most important).
    // Note 2: We upload the Clover reports only after having built all the repositories with Maven since we only want
    // to get a new directory created at http://maven.xwiki.org/site/clover/ if we've been able to generate Clover
    // reports for all repositorirunCloverAndGenerateReportes (otherwise it would just clutter the hard drive for no value).
    stage("Publish Clover Reports") {
        def prefix = "clover-"
        ["commons", "rendering", "platform"].each() { repoName ->
            dir("xwiki-${repoName}/target/site") {
                if (repoName != 'commons') {
                    prefix = "${prefix}+${repoName}"
                } else {
                    prefix = "${prefix}${repoName}"
                }
                sh "tar cvf ${prefix}-${dateString}.tar clover"
                sh "gzip ${prefix}-${dateString}.tar"
                def cloverTarget = "maven@maven.xwiki.org:public_html/site/clover"
                sh "scp ${prefix}-${dateString}.tar.gz ${cloverTarget}/${shortDateString}/"
                sh "rm ${prefix}-${dateString}.tar.gz"

                def cdCommand = "cd public_html/site/clover/${shortDateString}"
                def gunzipCommand = "gunzip ${prefix}-${dateString}.tar.gz"
                def tarCommand = "tar xvf ${prefix}-${dateString}.tar"
                def mvCommand = "mv clover ${prefix}-${dateString}"
                def rmCommand = "rm ${prefix}-${dateString}.tar"
                def commands = "${cdCommand}; ${gunzipCommand}; ${tarCommand}; ${mvCommand}; ${rmCommand}"
                sh "ssh maven@maven.xwiki.org '${commands}'"
            }
        }
    }
}
private void runCloverAndGenerateReport(def mvnHome, def localRepository, def cloverDir)
{
    // Generate Clover Report locally
    wrap([$class: 'Xvnc']) {
        // Note: With 2048m we got a OOM.
        withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx4096m']) {
            def commonPropertiesString = getSystemPropertiesAsString([
                'maven.repo.local' : "'${localRepository}'",
                'maven.clover.cloverDatabase' : "${cloverDir}/clover.db"
            ])
            // Skip the maximum number of checks to speed up the build
            def propertiesString = getSystemPropertiesAsString([
                'xwiki.revapi.skip' : 'true',
                'xwiki.checkstyle.skip' : 'true',
                'xwiki.enforcer.skip' : 'true',
                'xwiki.license.skip' : 'true',
                'maven.test.failure.ignore' : 'true'
            ])
            def profiles = "-Pclover,integration-tests,flavor-integration-tests,distribution,docker"
            // Use "nice" to reduce priority of the Maven process so that Jenkins stays as responsive as possible during
            // the build.
            sh "nice -n 5 mvn clean clover:setup install ${profiles} ${commonPropertiesString} ${propertiesString}"
            // Note: Clover reporting requires a display. Even though we're inside XVNC and thus have a display, let's
            // still configure the execution to be headless.
            sh "nice -n 5 mvn clover:clover -N ${commonPropertiesString} -Djava.awt.headless=true"
        }
    }
}
private def getSystemPropertiesAsString(def systemPropertiesAsMap)
{
    return systemPropertiesAsMap.inject([]) { result, entry ->
        result << "-D${entry.key}=${entry.value}"
    }.join(' ')
}
private void sendMail(def htmlContent)
{
    emailext (
        subject: "Global Coverage Failure - Build # ${env.BUILD_NUMBER}",
        body: htmlContent,
        mimeType: 'text/html',
        to: 'notifications@xwiki.org'
    )
}
private def getLatestReport()
{
    try {
        return new URL ("http://maven.xwiki.org/site/clover/latest.txt").getText()
    } catch (all) {
        // When no file exist, default to a fixed value we define.
        return "20171222-1835"
    }
}
