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
 * @return the list of Maven module paths having integration tests, found in the current directory and sub
 * directories. We will return all modules ending in {@code -test-tests} and {@code -test-docker} (e.g.
 * {@code xwiki-platform-administration-test-tests} and {@code xwiki-platform-administration-test-docker}).
 */
def call()
{
    def modules = []

    def dockerModules = findFiles(glob: '**/*-test-docker/pom.xml')
    // Note: don't use each() since it leads to unserializable exceptions
    for (def it in dockerModules) {
        modules.add(getParentPath(it.path))
    }

    def otherModules = findFiles(glob: '**/*-test-tests/pom.xml')
    // Note: don't use each() since it leads to unserializable exceptions
    for (def it in otherModules) {
        modules.add(getParentPath(it.path))
    }

    return modules
}

private def getParentPath(def path)
{
    return path.substring(0, path.lastIndexOf('/'))
}
