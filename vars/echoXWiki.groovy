#!/usr/bin/env groovy
import java.text.SimpleDateFormat

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

void call(text)
{
    // Note: since Jenkins doesn't disambiguate logs on agents (we don't know on what agent a log is output), we have
    // to do it ourselves by prefixing the message with the agent name.
    def sdf = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
    def nodeName = env['NODE_NAME'] ?: 'No node'
    echo "\u27A1 [${nodeName}][${sdf.format(new Date())}] ${text}"
}
