package org.apache.maven.plugin.surefire.report;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.PrintStream;
import org.apache.maven.surefire.report.ReportEntry;

/**
 * Outputs test system out/system err directly to the console
 * <p/>
 * Just a step on the road to getting the separation of reporting concerns
 * operating properly.
 *
 * @author Kristian Rosenvold
 */
public class ConsoleOutputDirectReporter
    implements Reporter
{
    private final PrintStream reportsDirectory;

    public ConsoleOutputDirectReporter( PrintStream reportsDirectory )
    {
        this.reportsDirectory = reportsDirectory;
    }

    public void testSetStarting( ReportEntry reportEntry )
    {
    }

    public void testSetCompleted( ReportEntry report )
    {
    }

    public void testStarting( ReportEntry report )
    {
    }

    public void testSucceeded( ReportEntry report )
    {
    }

    public void testSkipped( ReportEntry report )
    {
    }

    public void testError( ReportEntry report, String stdOut, String stdErr )
    {
    }

    public void testFailed( ReportEntry report, String stdOut, String stdErr )
    {
    }

    public void writeMessage( String message )
    {
    }

    public void writeMessage( byte[] b, int off, int len )
    {
        reportsDirectory.write( b, off, len );
    }

    public void reset()
    {
    }
    
    @Override
    public void closeReport() {
      // noop
    }
}
