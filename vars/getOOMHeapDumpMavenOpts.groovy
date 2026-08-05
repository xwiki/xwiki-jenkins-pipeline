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
 * Compute the Maven options that make the JVM dump the heap on an {@code OutOfMemoryError}, so that OOMs can be
 * analyzed post-mortem. Must be called from within a {@code node} since it inspects the agent filesystem and creates
 * the dump directory.
 *
 * @return the {@code -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...} options, or an empty string on agents that
 * don't have the shared Jenkins root (in which case we have nowhere reliable to write the dump).
 */
def call()
{
    // We want to get a memory dump on OOM errors and we need to make sure the memory dump directory exists.
    // Note that the user used to run the job on the agent must have the permission to create these directories.
    // Verify existence of /home/hudsonagent/jenkins_root so that we only set the dump path if it does.
    if (!fileExists('/home/hudsonagent/jenkins_root')) {
        return ''
    }
    def oomPath = "/home/hudsonagent/jenkins_root/oom/maven/${env.JOB_NAME}-${currentBuild.id}"
    sh "mkdir -p \"${oomPath}\""
    return "-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=\"${oomPath}\""
}
