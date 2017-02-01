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
//     name = 'application-faq'
//     goals = 'clean install' (default is 'clean deploy')
//     profiles = 'legacy,integration-tests,jetty,hsqldb,firefox' (default is 'quality,legacy,integration-tests')
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
            mvnHome = tool 'Maven'
        }
        stage('Build') {
            dir (config.name) {
                checkout scm
                // Execute the XVNC plugin (useful for integration-tests)
                wrap([$class: 'Xvnc']) {
                    withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx1024m']) {
                      try {
                          def goals = config.goals ?: 'clean deploy'
                          def profiles = config.profiles ?: 'quality,legacy,integration-tests'
                          sh "mvn ${goals} jacoco:report -P${profiles} -U -e -Dmaven.test.failure.ignore"
                          currentBuild.result = 'SUCCESS'
                      } catch (Exception err) {
                          currentBuild.result = 'FAILURE'
                          notifyByMail(currentBuild.result)
                          throw e
                      }
                   }
                }
            }
        }
        stage('Post Build') {
            // Archive the generated artifacts
            archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true
            // Save the JUnit test report
            junit testResults: '**/target/surefire-reports/TEST-*.xml'
        }
    }
}

def notifyByMail(String buildStatus) {
    buildStatus =  buildStatus ?: 'SUCCESSFUL'
    def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def summary = "${subject} (${env.BUILD_URL})"
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
