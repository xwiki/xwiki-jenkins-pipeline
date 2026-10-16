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

/**
 * Archive the heap dumps written by the Maven JVM when it ran out of memory (see configureJavaTool()), if any, since
 * they would be lost with the agent otherwise. A compressed dump is still hundreds of MB and is kept as long as its
 * build, so the dumps are only archived for the master and stable branches, to limit the disk space used when many
 * builds run out of memory (e.g. on pull request branches). The dumps that aren't archived are deleted, to free the
 * disk space of the agent.
 *
 * @param heapDumpDirectory the directory where the Maven JVM writes its heap dumps, or null if it's not configured
 * @param branchName the name of the branch being built, or null if the job isn't a multibranch one
 * @param buildName the name of the build (e.g. "TestRelease"), used to archive the dumps of each build of a job in a
 *        separate directory
 */
def call(heapDumpDirectory, branchName, buildName)
{
    try {
        archive(heapDumpDirectory, branchName, buildName)
    } catch (InterruptedException e) {
        // Let aborts and timeouts bubble up.
        throw e
    } catch (Exception e) {
        // Failing to archive the dumps (e.g. because the disk is full) mustn't replace the build failure or prevent
        // the processing of its results.
        echoXWiki "Failed to archive the Maven heap dumps found in ${heapDumpDirectory}: ${e.getMessage()}"
    }
}

private def archive(heapDumpDirectory, branchName, buildName)
{
    if (heapDumpDirectory
        && sh(script: "ls ${heapDumpDirectory}/*.hprof > /dev/null 2>&1", returnStatus: true) == 0)
    {
        if (!isMasterBranch(branchName) && !isStableBranch(branchName)) {
            echoXWiki "Deleting the Maven heap dumps found in ${heapDumpDirectory} since they're only archived for " +
                "the master and stable branches"
            sh "rm -f ${heapDumpDirectory}/*.hprof"
            return
        }
        // The dumps are named after the PID of the JVM (java_pid<N>.hprof), so they're archived in a directory named
        // after the build to know which build they come from, and to avoid a dump overwriting the dump of another
        // build of the same job with the same PID (possible with containerized agents).
        def archiveDirectory = "maven-heap-dumps/${buildName.replaceAll(/[^A-Za-z0-9._-]/, '_')}"
        echoXWiki "Archiving the Maven heap dumps found in ${heapDumpDirectory} in ${archiveDirectory}"
        // Compress the dumps since they're as big as the used heap and compress well. They're moved since
        // archiveArtifacts only takes files from the workspace.
        sh "gzip ${heapDumpDirectory}/*.hprof && rm -rf ${archiveDirectory} && mkdir -p ${archiveDirectory} " +
            "&& mv ${heapDumpDirectory}/*.hprof.gz ${archiveDirectory}/"
        archiveArtifacts artifacts: "${archiveDirectory}/*.hprof.gz", allowEmptyArchive: true
    }
}
