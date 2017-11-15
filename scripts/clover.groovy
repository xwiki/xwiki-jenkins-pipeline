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

// Computes the full Clover TPC for the XWiki project, taking into account all tests located in various repos:
// xwiki-commons, xwiki-rendering and xwiki-platform.
// This script should be loaded by a standard Jenkins Pipeline job, using the "Pipeline script from SCM" option.
node() {
    def mvnHome
    def localRepository
    def cloverDir
    stage('Preparation') {
        def workspace = pwd()
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
    // each() has problems in pipeline, thus using a standard for()
    // See https://issues.jenkins-ci.org/browse/JENKINS-26481
    for (String repoName : ["xwiki-commons", "xwiki-rendering", "xwiki-platform"]) {
        stage("Cloverify ${repoName}") {
            dir (repoName) {
                git "https://github.com/xwiki/${repoName}.git"
                runCloverAndGenerateReport(mvnHome, localRepository, cloverDir)
            }
        }
    }
    stage("Publish Clover Reports") {
        def shortDateString = new Date().format("yyyyMMdd")
        def dateString = new Date().format("yyyyMMdd-HHmm")
        def prefix = "clover-"
        for (String repoName : ["commons", "rendering", "platform"]) {
            dir ("xwiki-${repoName}/target/site") {
                if (repoName != 'commons') {
                    prefix = "${prefix}+${repoName}"
                } else {
                    prefix = "${prefix}${repoName}"
                }
                sh "tar cvf ${prefix}-${dateString}.tar clover"
                sh "gzip ${prefix}-${dateString}.tar"
                sh "ssh maven@maven.xwiki.org mkdir -p public_html/site/clover/${shortDateString}"
                sh "scp ${prefix}-${dateString}.tar.gz maven@maven.xwiki.org:public_html/site/clover/${shortDateString}/"
                sh "rm ${prefix}-${dateString}.tar.gz"
                sh "ssh maven@maven.xwiki.org 'cd public_html/site/clover/${shortDateString}; gunzip ${prefix}-${dateString}.tar.gz; tar xvf ${prefix}-${dateString}.tar; mv clover ${prefix}-${dateString};rm ${prefix}-${dateString}.tar'"
            }
        }
    }
}
def runCloverAndGenerateReport(def mvnHome, def localRepository, def cloverDir) {
    wrap([$class: 'Xvnc']) {
        withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx4096m']) {
            sh "mvn -Dmaven.repo.local='${localRepository}' clean clover:setup install -Pclover,integration-tests,flavor-integration-tests,distribution -Dmaven.clover.cloverDatabase=${cloverDir}/clover.db -Dmaven.test.failure.ignore=true -Dxwiki.revapi.skip=true"
            sh "mvn -Dmaven.repo.local='${localRepository}' clover:clover -N -Dmaven.clover.cloverDatabase=${cloverDir}/clover.db"
        }
    }
}