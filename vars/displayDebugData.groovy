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
    echoXWiki "Debug Data:"

    // Note: if the executables don't exist, this won't fail the step thanks to "returnStatus: true".
    sh script: 'top -b -c -n 1 -w512', returnStatus: true
    sh script: 'lsof -i -P -n', returnStatus: true
    sh script: 'docker ps -a', returnStatus: true
    sh script: 'docker run --cap-add=NET_ADMIN --network=host --rm --entrypoint "/bin/sh" vimagick/iptables\
         -c "/sbin/iptables -S"', returnStatus: true
    sh script: "docker events --since '15m' --until '0m'", returnStatus: true

}
