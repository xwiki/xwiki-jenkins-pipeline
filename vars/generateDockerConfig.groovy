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

void call()
{
    // TODO: The try/catch is a protection to avoid failing the build. Remove it when the code inside is proved to work.
    try {
        withCredentials([string(credentialsId: 'xwikiorgci', variable: 'SECRET')]) {
            sh 'echo "{\\"auths\\":{\\"https://index.docker.io/v1/\\":{\\"auth\\": \\"\$(echo -n xwikiorgci:\$SECRET | base64)\\"}}}" > ~/.docker/config.json'
                }
    } catch(Exception e) {
        echoXWiki "Failed to generate config.json file: ${e.message}"
    }
}
