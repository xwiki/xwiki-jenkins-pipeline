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

void call(def dockerHubSecretId, def dockerHubUserId)
{
    // Don't log in if the secret id is not not defined (null or empty string).
    if (dockerHubSecretId) {
        withCredentials([string(credentialsId: dockerHubSecretId, variable: 'SECRET')]) {
            sh 'mkdir -p ~/.docker'
            // Note: By default Jenkins starts shell scripts with flags -xe. -x enables additional logging. -e makes
            // the script exit if any command inside returns non-zero exit status.
            // Since we don't want to have the secret printed in the CI logs, we disable passing -x
            sh '#!/bin/sh -e\n' + "echo \"{\\\"auths\\\":{\\\"https://index.docker.io/v1/\\\":{\\\"auth\\\": \\\"\$(echo -n ${dockerHubUserId}:\$SECRET | base64)\\\"}}}\" > ~/.docker/config.json"
        }
    }
}
