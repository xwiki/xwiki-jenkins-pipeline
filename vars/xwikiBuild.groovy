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
import hudson.FilePath
import hudson.util.IOUtils
import javax.xml.bind.DatatypeConverter
import hudson.tasks.test.AbstractTestResultAction
import com.cloudbees.groovy.cps.NonCPS
import java.text.SimpleDateFormat

// If you need to setup a Jenkins instance where the following script will work you'll need to:
//
// - Configure Global tools:
//   - One named 'Maven' for Maven,
//   - One named 'java7' for Java 7
//   - One named 'official' for Java 8
// - Configure a Global Pipeline library
//   - Name: 'XWiki'
//   - Version: 'master'
//   - Enable 'Load implicitly'
//   - Choose modern SCM and then GitHub:
//     - owner: 'xwiki'
//     - repository: 'xwiki-jenkins-pipeline'
// - Have the following plugins installed:
//   - XVnc plugin. You'll also need to have the "vncserver" executable available in the path
//   - Email Extension plugin (provides emailext() API)
//   - Build Timeout plugin (provides timeout() API)
//   - Pipeline Utility Steps plugin (provides readMavenPom() API)
//   - Pipeline Maven Integration plugin (provides withMaven() API)
//   - Groovy Post Build plugin (provides the 'manager' variable)

/**
 * @param name a string representing the current build
 */
void call(name = 'Default', body)
{
    echoXWiki "Calling Jenkinsfile for build [{$name}]"
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    printConfigurationProperties(config)

    // Does the following:
    // - By default keep the last 20 builds
    //   - This behavior can be overridden by using the "daysToKeepStr" property, in which case the builds are kept for
    //     that many number of days
    // - Only keep builds for the last configured number of days (30 by default)
    //   - Special handling for contrib projects which have a lot less activity and for which we prefer to keep a number
    //     of builds instead rather than be time-based. We keep 20 builds.
    // - Disable concurrent builds to avoid rebuilding whenever a new commit is made. The commits will accumulate till
    //   the previous build is finished before starting a new one.
    //   Note 1: this is limiting concurrency per branch only.
    //   Note 2: This needs to be one of the first code executed which is why it's the first step we execute.
    //   See https://thepracticalsysadmin.com/limit-jenkins-multibranch-pipeline-builds/ for details.
    // -  Make sure projects are built at least once a month because SNAPSHOT older than one month are deleted
    //    by the Nexus scheduler.
    def buildDiscardStrategy = [$class: 'LogRotator']
    if (config.daysToKeepStr) {
        buildDiscardStrategy.put('daysToKeepStr', config.daysToKeepStr.toString())
        echoXWiki "Keep the builds for the last ${config.daysToKeepStr} days"
    } else {
        def buildsToKeep = 20
        buildDiscardStrategy.put('numToKeepStr', buildsToKeep.toString())
        echoXWiki "Keep the last $buildsToKeep builds"
    }
    def projectProperties = [
        [$class: 'BuildDiscarderProperty', strategy: buildDiscardStrategy],
        disableConcurrentBuilds(),
        pipelineTriggers([cron("@monthly")])
    ]

    // Process job properties overrides.
    def allProperties = []
    // Note: we add the overridden job properties first since the properties() step will honor the values that come
    // first and ignore further ones. This allows the Jenkinsfile to take precedence.
    echoXWiki "Custom job properties: ${config.jobProperties}"
    if (config.jobProperties) {
        allProperties.addAll(config.jobProperties)
    }
    allProperties.addAll(projectProperties)
    echoXWiki "Full job properties: ${allProperties}"
    properties(allProperties)

    def mavenTool
    def javaMavenConfig
    stage("Preparation for ${name}") {
        // Get the Maven tool.
        // Note: We use an empty string by default, in order to not use the Global tools from Jenkins. We run our
        // builds on the XWiki Docker build image which has Maven pre-installed. If the caller passes a non-empty string
        // then the Maven setup will need to be defined in Jenkins's Global tools.
        mavenTool = config.mavenTool?.trim() ?: ''

        // Check if the build should be aborted
        if (config.disabled) {
            currentBuild.result = 'ABORTED'
            error "Aborting build since it's disabled explicitly..."
        }

        if (!config.skipCheckout) {
            echoXWiki "SCM checkout with changelog set to [${config.skipChangeLog}]"
            checkout changelog: config.skipChangeLog ?: false, scm: scm
        }

        // Configure the version of Java to use
        def pom = readMavenPom file: getPOMFile(config)
        javaMavenConfig = configureJavaTool(config, pom)

        // Generate a ~/.docker/config.json file containing authentication data for Dockerhub so that all operations
        // done on Dockerhub are done while authenticated, which prevents the pull-rate issue.
        def dockerHubSecretId = config.dockerHubSecretId == null ? 'xwikici' : config.dockerHubSecretId
        def dockerHubUserId = config.dockerHubUserId ?: 'xwikici'
        generateDockerConfig(dockerHubSecretId, dockerHubUserId)

        // Force removal of unused docker containers, networks, dangling images, volumes to avoid Docker taking more and
        // more space over time due to leftovers (e.g. https://github.com/testcontainers/testcontainers-java/issues/5667
        // and https://github.com/testcontainers/testcontainers-java/issues/3558)
        sh script: 'docker system prune --volumes -f', returnStatus: true

        // Display some environmental information that can be useful to debug some failures
        // Note: if the executables don't exist, this won't fail the step thanks to "returnStatus: true".
        sh script: 'ps -ef', returnStatus: true
        sh script: 'netstat -nltp', returnStatus: true
        // Note: the "|| true" allows the sh command to fail (when firefox is not installed for example) without
        // failing the job build.
        def firefoxVersion = sh script: 'firefox -version || true', returnStdout: true
        if (firefoxVersion) {
            echoXWiki "Firefox version installed: ${firefoxVersion}"
        }

        // Set config.sonar = true if the sonar:sonar goal is set in config.goals.
        if (config.goals?.contains('sonar:sonar')) {
            config.sonar = true
        }
    }
    stage("Build for ${name}") {
        // Execute the XVNC plugin (useful for integration-tests)
        wrapInXvnc(config) {
            // Execute the Maven build.
            // Note that withMaven() will also perform some post build steps:
            // - Archive and fingerprint generated Maven artifacts and Maven attached artifacts (if archiveArtifacts
            //   is set to true, see above)
            // - Publish JUnit / Surefire reports (if the Jenkins JUnit Plugin is installed)
            // - Publish Findbugs reports (if the Jenkins FindBugs Plugin is installed)
            // - Publish a report of the tasks ("FIXME" and "TODO") found in the java source code
            //   (if the Jenkins Tasks Scanner Plugin is installed)
            echoXWiki "Using Java tool: ${javaMavenConfig.jdk}"
            echoXWiki "Using Maven tool: ${mavenTool ?: 'None, using pre-installed mvn executable on host'}"
            echoXWiki "Using Maven options: ${javaMavenConfig.mavenOpts}"
            def archiveArtifacts = config.archiveArtifacts == null ? false : config.archiveArtifacts
            echoXWiki "Artifact archiving: ${archiveArtifacts}"
            def fingerprintDependencies = config.fingerprintDependencies == null ? false :
                config.fingerprintDependencies
            echoXWiki "Dependencies fingerprinting: ${fingerprintDependencies}"
            // Note: We're not passing "mavenOpts" voluntarily, see configureJavaTool()
            def publishers = [
                artifactsPublisher(disabled: !archiveArtifacts),
                dependenciesFingerprintPublisher(disabled: !fingerprintDependencies),
                // TODO: Remove once https://jira.xwiki.org/browse/XINFRA-382 is fixed
                invokerPublisher(disabled: true)
            ]
            // Note: withMaven is concatenating any passed "mavenOpts" with env.MAVEN_OPTS. Thus in order to fully
            // control the maven options used we set env.MAVEN_OPTS to empty.
            env.MAVEN_OPTS = ''
            wrapInWithMaven(maven: mavenTool, jdk: javaMavenConfig.jdk, mavenOpts: javaMavenConfig.mavenOpts,
                    options: publishers)
            {
                try {
                    def goals = computeMavenGoals(config)
                    echoXWiki "Using Maven goals: ${goals}"
                    def profiles = getMavenProfiles(config, env)
                    echoXWiki "Using Maven profiles: ${profiles}"
                    def pomFile = getPOMFile(config)
                    def pom = readMavenPom file: pomFile
                    echoXWiki "Using POM file: ${pom}"
                    def branchName = env['BRANCH_NAME']
                    // If we're building a feature branch that needs to be deployed, we set first its version so that
                    // it's deployed with a specific version based on the branch name.
                    if (isFeatureDeploymentBranch(branchName)) {
                        def pomVersion = pom.version
                        if (!pomVersion) {
                            pomVersion = pom.parent.version
                        }
                        def index = pomVersion.size() - "-SNAPSHOT".size();
                        def actualVersion = pomVersion.substring(0, index)
                        def branchVersion = "${actualVersion}-${branchName}-SNAPSHOT"
                        if (pom.parent) {
                            // Change the parent version, but only if one exist for that parent
                            echoXWiki "Setting parent to: ${branchVersion}"
                            sh script: "mvn versions:update-parent -DallowSnapshots=true -DparentVersion=[${pom.parent.version}],[${branchVersion}] -N"
                        }
                        // Change the project version
                        // We do those changes from the root since sub modules only define the parent
                        echoXWiki "Setting version to: ${branchVersion}"
                        sh script: "mvn versions:set -DnewVersion=${branchVersion} -P${profiles}"
                        // We need to also reset the commons.version property from the pom.xml if it's building commons.
                        // The sed command is inspired from the release script, we don't want it to fail the build if
                        // the property cannot be found hence the returnStatus: true
                        sh script: "sed -e  \"s/<commons.version>.*<\\/commons.version>/<commons.version>${branchVersion}<\\/commons.version>/\" -i pom.xml", returnStatus: true
                    }

                    def properties = getMavenSystemProperties(config, "${NODE_NAME}")
                    echoXWiki "Using Maven properties: ${properties}"
                    def javadoc = ''
                    if (config.javadoc == null || config.javadoc == true) {
                        javadoc = 'javadoc:javadoc -Ddoclint=all'
                        echoXWiki "Enabling javadoc validation"
                    }
                    def timeoutThreshold = config.timeout ?: 240
                    echoXWiki "Using timeout: [${timeoutThreshold}] minutes"
                    // Display the java version for information (in case it's useful to debug some specific issue)
                    echoXWiki 'Java version used:'
                    sh script: 'java -version', returnStatus: true
                    // Abort the build if it takes more than the timeout threshold (in minutes).
                    timeout(timeoutThreshold) {
                        def mavenFlags = config.mavenFlags ?: '-U -e'
                        wrapInSonarQube(config) {
                            sh "mvn -f ${pomFile} ${goals} -P${profiles} ${mavenFlags} ${properties} ${javadoc}"
                        }
                    }
                } catch (InterruptedException e) {
                    // This can happen when the timeout() step reaches the timeout. We need to let this bubble up so
                    // that Jenkins can coordinate the stopping of all threads & builds that execute in parallel.
                    echoXWiki "XWiki build [${name}] interrupted due to timeout"
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    echoXWiki "[DEBUG] Cause for the timeout:\n${sw.toString()}"
                    displayDebugData()
                    Thread.currentThread().interrupt();
                    // Note: Don't send email on an interrupted build.
                } catch (Exception e) {
                    // - If this line is reached it means the build has failed (other than for failing tests) or has
                    //   been aborted (because we told maven above to not stop on test failures!)
                    // - We stop the build by throwing the exception.
                    // - Note that withMaven() doesn't set any build result in this case but we don't need to set any
                    //   since we're stopping the build!
                    // - Don't send emails for aborts! We discover aborts by checking for exit code 143.
                    // - Also don't send emails for process kills since it's an environment issue and not an XWiki
                    //   source problem (this happens when the exit code is 137).
                    displayDebugData()
                    if (!e.getMessage()?.contains('exit code 143') && !e.getMessage()?.contains('exit code 137')
                        && !config.skipMail)
                    {
                        sendMail('ERROR', name)
                    }
                    throw e
                }
            }
        }
    }
    stage("Post Build for ${name}") {
        // If the job made it to here it means the Maven build has either succeeded or some tests have failed.
        // If the build has succeeded, then currentBuild.result is null (since withMaven doesn't set it in this case).
        if (currentBuild.result == null) {
            currentBuild.result = 'SUCCESS'
        }

        // Save videos generated by Docker-based tests, if any.
        // Note: This can generate some not nice stack trace in the logs,
        // see https://issues.jenkins-ci.org/browse/JENKINS-51913
        echoXWiki "Looking for test videos in ${pwd()}"
        archiveArtifacts artifacts: '**/target/**/*.flv', allowEmptyArchive: true

        // Save images generated by functional tests, if any
        // Note: This can generate some not nice stack trace in the logs,
        // see https://issues.jenkins-ci.org/browse/JENKINS-51913
        // Note: we look for screenshots only in the screenshots directory to avoid false positives such as PNG images
        // that would be located in a XWiki distribution located in target/.
        echoXWiki "Looking for test failure images in ${pwd()}"
        archiveArtifacts artifacts: '**/target/**/screenshots/*.png', allowEmptyArchive: true

        // Archive WCAG reports, if any.
        // Note: This can generate some not nice stack trace in the logs,
        // see https://issues.jenkins-ci.org/browse/JENKINS-51913
        if (config.properties && config.properties.contains('-Dxwiki.test.ui.wcag=true')) {
            echoXWiki "Looking for WCAG test results in ${pwd()}"
            archiveArtifacts artifacts: '**/target/**/wcag-reports/wcag*.txt', allowEmptyArchive: true
        }

        echoXWiki "Current build status after withMaven execution: ${currentBuild.result}"

        // For each failed test, find if there's a screenshot for it taken by the XWiki selenium tests and if so
        // embed it in the failed test's description. Also check if failing tests are flickers.
        if (currentBuild.result != 'SUCCESS') {
            def failingTests = getFailingTests()
            echoXWiki "Failing tests: ${failingTests.collect { "${it.getClassName()}#${it.getName()}" }}"
            if (!failingTests.isEmpty()) {
                echoXWiki "Attaching screenshots to test result pages (if any)..."
                attachScreenshotToFailingTests(failingTests)

                // Check for false positives & Flickers.
                echoXWiki "Checking for false positives and flickers in build results..."
                def containsFalsePositivesOrOnlyFlickers = checkForFalsePositivesAndFlickers(failingTests)

                // Also send a mail notification when there are not only false positives or flickering tests.
                // Update 2020-10-31: Temporarily only send mails when there are failing non-functional tests to reduce
                // the number of emails sent, until we fix functional tests stability. See https://bit.ly/34GTVBe
                echoXWiki "Checking if email should be sent or not"
                if (!containsFalsePositivesOrOnlyFlickers && !config.skipMail
                    && containsNonFunctionalFailingTests(failingTests))
                {
                    sendMail(currentBuild.result, name)
                } else {
                    echoXWiki "No email sent even if some tests failed because they contain only flickering tests!"
                    echoXWiki "Considering job as successful!"
                    currentBuild.result = 'SUCCESS'
                }
            }
        }
    }
}

private def containsNonFunctionalFailingTests(def failingTests)
{
    for (failingTest in failingTests) {
        if (!failingTest.className.contains("IT")) {
            return true
        }
    }
    return false
}

private def getPOMFile(def config)
{
    return config.pom ?: 'pom.xml'
}

private def getMavenSystemProperties(config, nodeName)
{
    def properties = config.properties ?: ''

    // Add a system property that represents the agent name so that whenever a test fails, we can display the agent
    // on which it is executed in order to make it easier for debugging (it'll appear in the jenkins page for the
    // failing test (see XWikiDockerExtension which prints it).
    properties = "${properties} -DjenkinsAgentName=\"${nodeName}\""

    // Note: We use -Dmaven.test.failure.ignore so that the maven build continues till the
    // end and is not stopped by the first failing test. This allows to get more info from the
    // build (see all failing tests for all modules built). Also note that the build is marked
    // unstable when there are failing tests by the JUnit Archiver executed during the
    // 'Post Build' stage below.
    // Note: "--no-transfer-progress" is used to avoid the download progress indicators which do
    // not display well in a non-interactive shell and which use a lot of console log space.
    properties = "--no-transfer-progress -Dmaven.test.failure.ignore ${properties}"

    // When sonar is active (sonar = true) then also pass the "sonar.branch.name" maven property so that SonarQube
    // pushes the analysis to the right branch on sonarcloud. Note that we only pass it when not analyzing the main
    // branch as suggested by https://community.sonarsource.com/t/clarify-the-use-of-sonar-branch-name/18872/3
    def branchName = env['BRANCH_NAME']
    if (config.sonar && !isMasterBranch(branchName)) {
        properties = "${properties} -Dsonar.branch.name=${branchName}"
    }

    // Have functional tests retry twice in case of error. This is done to try to reduce the quantity of flickers.
    // TODO: put back the retry when the build is in a better shape and when Surefirex 3.x doesn't fail anymore in
    // running the tests for xwiki-platform-rendering-macro-python
    //properties = "${properties} -Dfailsafe.rerunFailingTestsCount=2"

    return properties
}

private void printConfigurationProperties(config)
{
    def buffer = new StringBuilder()
    config.each{ k, v -> buffer.append("[${k}] = [${v}]\n") }
    echoXWiki "Passed configuration properties:\n${buffer.toString()}"
}

private def getMavenProfiles(config, env)
{
    return config.profiles ?: 'quality,legacy,integration-tests,docker,jetty,hsqldb,firefox'
}

private def wrapInWithMaven(config, closure)
{
    try {
        withMaven(config) {
            closure()
        }
    } catch (Throwable e) {
        // Unexpected error. Since we execute all tests with -Dmaven.test.failure.ignore (ignore test failures), if
        // the withMaven step exits with an exception, the junit publisher may not be executed and thus the build may
        // be marked as successful when in reality it's not.
        echoXWiki "The withMaven step has stopped unexpectedly and the JUnit test results may not be accurate."
        echoXWiki "Marking the build in error since something very wrong happened."
        currentBuild.result = 'ERROR'
        throw e;
    }
}

private def wrapInXvnc(config, closure)
{
    def isXvncEnabled = config.xvnc == null ? true : config.xvnc
    if (isXvncEnabled) {
        wrap([$class: 'Xvnc']) {
            closure()
        }
    } else {
        echoXWiki "Xvnc disabled, building without it!"
        closure()
    }
}

private def wrapInSonarQube(config, closure)
{
    if (config.sonar) {
        withSonarQubeEnv('sonar') {
            // Note: SonarQube taskId is automatically attached to the pipeline context
            closure()
        }
        // Check the SonarQube quality gates
        // Note: the timeout is just in case something goes wrong
        timeout(time: 1, unit: 'HOURS') {
            // Reuse taskId previously collected by withSonarQubeEnv
            def qg = waitForQualityGate()
            if (qg.status != 'OK') {
                error "Pipeline aborted due to quality gate failure: ${qg.status}"
            }
        }
    } else {
        closure()
    }
}

private def computeMavenGoals(config)
{
    def goals = config.goals
    if (!goals) {
        // Use "deploy" goal for the master branch and the "stable-*" branches only and "install" for all branches.
        // This is to avoid having branches with the same version in pom.xml, polluting the maven snapshot repo,
        // overwriting one another.
        def branchName = env['BRANCH_NAME']
        if (branchName != null && (isMasterBranch(branchName) || isStableBranch(branchName) || isFeatureDeploymentBranch(branchName))) {
            goals = "deploy"
        } else {
            goals = "install"
        }
        goals = "clean ${goals}"
    }
    return goals
}

private def isStableBranch(def branchName)
{
    return branchName.startsWith('stable-')
}

/**
 * Test if a branch is about a feature that needs to be deployed, and not just installed.
 */
private def isFeatureDeploymentBranch(def branchName) {
    return branchName.startsWith('feature-deploy-');
}

/**
 * Create a FilePath instance that points either to a file on the master node or a file on a remote agent node.
 */
private def createFilePath(String path)
{
    if (env['NODE_NAME'] == null) {
        error "envvar NODE_NAME is not set, probably not inside an node {} or running an older version of Jenkins!"
    } else if (env['NODE_NAME'].equals("master")) {
        return new FilePath(new File(path))
    } else {
        return new FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), path)
    }
}

/**
 * Attach the screenshot of failing XWiki Selenium tests to failed test descriptions.
 * The screenshot is preserved after the workspace gets cleared by a new build.
 *
 * To make this script works the following needs to be setup on the Jenkins instance:
 * <ul>
 *   <li>Install the <a href="http://wiki.jenkins-ci.org/display/JENKINS/Groovy+Postbuild+Plugin">Groovy Postbuild
 *       plugin</a>. This exposes the "manager" variable needed by the script.</li>
 *   <li>Add the required security exceptions to http://<jenkins server ip>/scriptApproval/ if need be.</li>
 *   <li>Install the <a href="https://wiki.jenkins-ci.org/display/JENKINS/PegDown+Formatter+Plugin">Pegdown Formatter
 *       plugin</a> and set the description syntax to be Pegdown in the Global Security configuration
 *       (http://<jenkins server ip>/configureSecurity).</li>
 * </ul>
 */
private def attachScreenshotToFailingTests(def failingTests)
{
    // Go through each failed test in the current build.
    for (def failedTest : failingTests) {
        // Compute the test's screenshot file name.
        def testClass = failedTest.className
        def testName = failedTest.name

        def targetDirectory = computeTargetDirectoryForTest(failedTest)
        if (!targetDirectory) {
            // We couldn't compute the target directory, move to the next test!
            echoXWiki "Failed to find target directory for test [${testClass}#${testName}]"
            continue
        }
        def imageAbsolutePath = findScreenshotFile(failedTest, targetDirectory)

        // If a screenshot exists...
        if (imageAbsolutePath) {
            echoXWiki "Attaching screenshot to description: [${imageAbsolutePath}]"

            // Build a base64 string of the image's content.
            def imageDataStream = imageAbsolutePath.read()
            byte[] imageData = IOUtils.toByteArray(imageDataStream)
            def imageDataString = "data:image/png;base64," + DatatypeConverter.printBase64Binary(imageData)

            def testResultAction = failedTest.getParentAction()

            // Build a description HTML to be set for the failing test that includes the image in Data URI format.
            def imgText = """<img style="width: 800px" src="${imageDataString}" />"""
            def description = """<h3>Screenshot</h3><a href="${imageDataString}">${imgText}</a>"""

            // Only modify the description if the test page hasn't been modified already
            if (!description.equals(testResultAction.getDescription(failedTest))) {
                // Set the description to the failing test and save it to disk.
                testResultAction.setDescription(failedTest, description)
                // Clear potentially problematic non-serializable object reference, after we've used it.
                // See https://github.com/jenkinsci/pipeline-plugin/blob/master/TUTORIAL.md#serializing-local-variables
                testResultAction = null
                saveCurrentBuildChanges()
            }
        } else {
            echoXWiki "No screenshot found for test [${testClass}#${testName}] on ${NODE_NAME}"
            sh script: "ls -alg ${targetDirectory}", returnStatus: true
        }
    }
}

private def findScreenshotFile(def failedTest, def targetDirectory)
{
    // The screenshot can have several possible file names and locations, we check all.
    // Selenium 1 test screenshots.
    def imageAbsolutePath1 = new FilePath(targetDirectory, "selenium-screenshots")
    // Selenium 2 test screenshots.
    def imageAbsolutePath2 = new FilePath(targetDirectory, "screenshots")
    // If screenshotDirectory system property is not defined we save screenshots in the tmp dir so we must also
    // support this.
    def imageAbsolutePath3 = createFilePath(System.getProperty("java.io.tmpdir"))

    // Determine which one exists, if any.
    return findScreenshotFileForPattern(imageAbsolutePath1, failedTest) ?:
        findScreenshotFileForPattern(imageAbsolutePath2, failedTest) ?:
            findScreenshotFileForPattern(imageAbsolutePath3, failedTest)
}

private def findScreenshotFileForPattern(def directoryFilePath, def failedTest)
{
    def files = [] as Set
    // Remove the serialized parameters from the test name FTM since we output failing test image names without it.
    // The best fix would be to modify the Docker-based test framework to add the parameters but I don't know how to do
    // that ATM (i.e. what JUnit API to call to get it).
    def normalizedTestName = normalizeTestName(failedTest.name.toString())
    if (directoryFilePath.exists()) {
        files.addAll(directoryFilePath.list("*${failedTest.className}-${normalizedTestName}*.png"))
        files.addAll(directoryFilePath.list("*${failedTest.simpleName}-${normalizedTestName}*.png"))
    }
    if (files.size() > 1) {
        echoXWiki "Found several matching screenshots which should not happen (something needs to be fixed): ${files}"
        return null
    } else if (files.size() == 1) {
        return files[0]
    } else {
        echoXWiki "No matching screenshot found for [*${failedTest.className}-${normalizedTestName}*.png] or [*${failedTest.simpleName}-${normalizedTestName}*.png] inside [${directoryFilePath.remote}]"
        return null
    }
}

private def computeTargetDirectoryForTest(def caseResult)
{
    // A CaseResult has a SuiteResult as parent which holds the JUnit XML file path, from which we can infer the
    // target directory for the passed test.

    // Example of value for suiteResultFile (it's a String):
    //   /Users/vmassol/.jenkins/workspace/blog/application-blog-test/application-blog-test-tests/target/
    //     surefire-reports/TEST-org.xwiki.blog.test.ui.AllTests.xml
    def suiteResultFile = caseResult.getSuiteResult().getFile()
    if (suiteResultFile == null) {
        return
    }

    // Compute the screenshot's location on the build agent.
    // Example of target folder path:
    //   /Users/vmassol/.jenkins/workspace/blog/application-blog-test/application-blog-test-tests/target
    def targetDirectory = createFilePath(suiteResultFile).getParent().getParent()

    // When executing docker-based tests as part of the main build (ie with the default configuration), we use a
    // subdirectory inside the target directory, and it's in it that we save the screenshots and videos. Thus we need
    // to test for that directory's existence and if it exists, return it.
    if (targetDirectory.exists()) {
        def subDirectory = targetDirectory.child("hsqldb_embedded-default-default-jetty_standalone-default-firefox")
        if (subDirectory.exists()) {
            targetDirectory = subDirectory
        }
    }

    return targetDirectory;
}

/**
 * Check for false positives for known cases of failures not related to code + check for test flickers.
 *
 * @return true if the build has false positives or if there are only flickering tests
 */
private def checkForFalsePositivesAndFlickers(def failingTests)
{
    // Step 1: Check for false positives
    def containsFalsePositives = checkForFalsePositives()

    // Step 2: Check for flickers
    def containsOnlyFlickers = checkForFlickers(failingTests)

    return containsFalsePositives || containsOnlyFlickers
}

/**
 * Check for test flickers, and modify test result descriptions for tests that are identified as flicker. A test is
 * a flicker if there's a JIRA issue having the "Flickering Test" custom field containing the FQN of the test in the
 * format "<java package name>#<test name>".
 *
 * @return true if the failing tests only contain flickering tests
 */
private def checkForFlickers(def failingTests)
{
    def knownFlickers = getKnownFlickeringTests()
    echoXWiki "Known flickering tests: ${knownFlickers}"

    // For each failed test, check if it's in the known flicker list.
    Set foundFlickers = []
    boolean containsOnlyFlickers = true
    boolean isModified = false
    failingTests.each() { testResult ->
        // Construct a normalized test name made of <test class name>#<method name>
        // Note: The call to toString() is important to get a String and not a GString so that contains() will work
        // (since otherwise equals() will fail between a String and a GString)
        def normalizedTestName = normalizeTestName(testResult.name.toString())
        def testName = "${testResult.className}#${normalizedTestName}".toString()
        echoXWiki "Analyzing test [${testName}] for flicker ..."
        if (knownFlickers.containsKey(testName)) {
            // Add the information that the test is a flicker to the test's description. Only display this
            // once (a Jenkinsfile can contain several builds and thus this code can be called several times
            // for the same tests, as the failing tests passed are the failing tests for the whole job, see
            // getFailingTests(). We haven't found a way to get the failing tests only for the current withMaven
            // execution).
            def flickeringText =
              "<h3 style='color:red'>This is a <a href='${knownFlickers.get(testName)}'>flickering</a> test</h3>"
            if (testResult.getDescription() == null || !testResult.getDescription().contains(flickeringText)) {
                testResult.setDescription("${flickeringText}${testResult.getDescription() ?: ''}")
                isModified = true
            } else {
                // For debugging
                echoXWiki "Flicker [${testName}] - Description = [${testResult.getDescription()}]"
            }
            echoXWiki "   [${testName}] is a flicker!"
            foundFlickers.add(testName)
        } else {
            echoXWiki "   [${testName}] is not a flicker"
            // This is a real failing test, thus we'll need to send the notification email...
            containsOnlyFlickers = false
        }
    }

    if (foundFlickers) {
        def badgeText = 'Contains some flickering tests'
        def badgeFound = isBadgeFound(badgeText)
        if (!badgeFound) {
            manager.addWarningBadge(badgeText)
        }
        // Replace the existing summary with the accrued list of flickers found
        manager.removeSummaries()
        def summary = manager.createSummary("warning.gif")
        summary.appendText("Flickering tests<ul>", false, false, false, 'red')
        foundFlickers.each() {
            summary.appendText("<li><a href='${knownFlickers.get(it)}'>${it}</a></li>", false, false, false, 'red')
        }
        summary.appendText("</ul>", false, false, false, 'red')
        isModified = true
    }

    if (isModified) {
        // Persist changes
        saveCurrentBuildChanges()
    }

    return containsOnlyFlickers
}

/**
 * @return all known flickering tests from JIRA in the format
 *         {@code org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures}
 */
@NonCPS
private def getKnownFlickeringTests()
{
    def knownFlickers = [:]
    def url = "https://jira.xwiki.org/sr/jira.issueviews:searchrequest-xml/temp/SearchRequest.xml?".concat(
            "jqlQuery=%22Flickering%20Test%22%20is%20not%20empty%20and%20resolution%20=%20Unresolved")
    def root = new XmlSlurper().parseText(url.toURL().text)
    // Note: slurper nodes are not serializable, hence the @NonCPS annotation above.
    def packageName = ''
    root.channel.item.customfields.customfield.each() { customfield ->
        if (customfield.customfieldname == 'Flickering Test') {
            def trimSpaces = {
                def trimmedIt = it.trim()
                // When a leading space is adding in jira, the resulting XML value we get for it is "&nbsp;".
                trimmedIt.startsWith('&nbsp;') ? trimmedIt - '&nbsp;' : trimmedIt
            }
            customfield.customfieldvalues.customfieldvalue.text().split('\\|').each() {
                def trimmedValue = trimSpaces(it)
                // Check if a package is specified and if not use the previously found package name
                // This is an optimization to make it shorter to specify several tests in the same test class.
                // e.g.: "org.xwiki.test.ui.extension.ExtensionTest#testUpgrade,testUninstall"
                def fullName
                int pos = trimmedValue.indexOf('#')
                if (pos > -1) {
                    packageName = trimmedValue.substring(0, pos)
                    fullName = trimmedValue
                } else {
                    fullName = "${packageName}#${trimmedValue}"
                }
                // Remove the part between "{" and "}" since we don't use test methods which differ only by their
                // parameters, and removing this make the jira issues more stable against refactorings. Also prevents
                // user mistakes.
                // Note: the toString() is there to convert from a GString to a String. Without it, we're getting some
                // "No signature of method: java.lang.String.containsKey() is applicable for argument types" error.
                fullName = normalizeTestName(fullName.toString())
                knownFlickers.put(fullName, customfield.parent().parent().link.text())
            }
        }
    }

    return knownFlickers
}

@NonCPS
private def normalizeTestName(value)
{
    def newValue
    // Support both <test name prefix{...}> and <test name prefix(...)> since it seems that Jenkins could have changed
    // the way it reports test names (Jenkins or JUnit).
    def pos = value.indexOf('{')
    if (pos < 0) {
        pos = value.indexOf('(')
    }
    if (pos > -1) {
        // Remove till end of string
        newValue = value.substring(0, pos)
    } else {
        newValue = value
    }
    return newValue
}

/**
 * @return the failing tests for the current build as a list of {@code hudson.tasks.junit.CaseResult} objects.
 */
// TODO: Note that this is currently not working as it returns all failing tests from all maven executions so far.
// See also https://issues.jenkins.io/browse/JENKINS-49339
// currentBuild.rawBuild is non-serializable which is why we need the @NonCPS annotation.
// Search for "rawBuild" on https://ci.xwiki.org/pipeline-syntax/globals#currentBuild
// Otherwise we get: Caused: java.io.NotSerializableException: org.jenkinsci.plugins.workflow.job.WorkflowRun
@NonCPS
private def getFailingTests()
{
    def failingTests
    def currentRun = currentBuild.rawBuild
    AbstractTestResultAction testResultAction = currentRun.getAction(AbstractTestResultAction.class)
    if (testResultAction != null) {
        // Note: getResultInRun() returns a https://javadoc.jenkins.io/plugin/junit/hudson/tasks/test/TestResult.html
        failingTests = testResultAction.getResult().getResultInRun(currentBuild.rawBuild).getFailedTests()
    } else {
        // No tests were run in this build, nothing left to do.
        failingTests = []
    }
    return failingTests
}
