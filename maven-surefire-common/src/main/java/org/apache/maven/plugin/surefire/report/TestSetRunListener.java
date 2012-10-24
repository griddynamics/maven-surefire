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

import org.apache.maven.plugin.surefire.runorder.StatisticsReporter;
import org.apache.maven.surefire.report.ConsoleLogger;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.PojoStackTraceWriter;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.RunStatistics;
import org.apache.maven.surefire.report.SimpleReportEntry;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.util.internal.ByteBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reports data for a single test set.
 * <p/>
 *
 * @author Kristian Rosenvold
 */
public class TestSetRunListener
    implements RunListener, ConsoleOutputReceiver, ConsoleLogger
{
    private static final boolean printTestMethodStatuses = true; 
    
    private static final String MARK_TEST_PASSED =            ".";
    private static final String MARK_TEST_FAILED =            "f";
    private static final String MARK_TEST_ERROR =             "e";
    private static final String MARK_TEST_SKIPPED =           "s";
    private static final String MARK_TEST_EXECUTION_TIMEOUT = "T";
    private static final String MARK_TEST_EXECUTION_ERROR =   "X";
  
    private final RunStatistics globalStatistics;

    private final TestSetStats detailsForThis;


    private final List<ByteBuffer> testStdOut = Collections.synchronizedList( new ArrayList<ByteBuffer>() );

    private final List<ByteBuffer> testStdErr = Collections.synchronizedList( new ArrayList<ByteBuffer>() );

    private final TestcycleConsoleOutputReceiver consoleOutputReceiver;

    private final boolean briefOrPlainFormat;

    private final StatelessXmlReporter simpleXMLReporter;

    private final ConsoleReporter consoleReporter;

    private final FileReporter fileReporter;

    private final StatisticsReporter statisticsReporter;
    
    private String currentTestClassName;
    private String currentTestMethodName;

    public TestSetRunListener( ConsoleReporter consoleReporter, FileReporter fileReporter,
                               StatelessXmlReporter simpleXMLReporter,
                               TestcycleConsoleOutputReceiver consoleOutputReceiver,
                               StatisticsReporter statisticsReporter, RunStatistics globalStats, boolean trimStackTrace,
                               boolean isPlainFormat, boolean briefOrPlainFormat )
    {
        this.consoleReporter = consoleReporter;
        this.fileReporter = fileReporter;
        this.statisticsReporter = statisticsReporter;
        this.simpleXMLReporter = simpleXMLReporter;
        this.consoleOutputReceiver = consoleOutputReceiver;
        this.briefOrPlainFormat = briefOrPlainFormat;
        this.detailsForThis = new TestSetStats( trimStackTrace, isPlainFormat );
        this.globalStatistics = globalStats;
    }

    public void info( String message )
    {
        if ( consoleReporter != null )
        {
            consoleReporter.writeMessage( message );
        }
    }

    public void writeTestOutput( byte[] buf, int off, int len, boolean stdout )
    {
        ByteBuffer byteBuffer = new ByteBuffer( buf, off, len );
        if ( stdout )
        {
            testStdOut.add( byteBuffer );
        }
        else
        {
            testStdErr.add( byteBuffer );
        }
        consoleOutputReceiver.writeTestOutput( buf, off, len, stdout );
    }

    // =====================================================================
    
    public void testSetStarting( final ReportEntry report )
    {
      if (currentTestClassName == null) {
        currentTestClassName = report.getName();
        
        detailsForThis.testSetStart();
        if ( consoleReporter != null )
        {
            consoleReporter.testSetStarting( report );
            if (printTestMethodStatuses) {
              consoleReporter.writeMessage("   |");
            }
        }
        consoleOutputReceiver.testSetStarting( report );
      } else {
        System.out.println("ERROR: a new test set is starting while a previous one is not completed: ["+currentTestClassName+"]");
      }
    }

    private void clearCapture()
    {
        testStdErr.clear();
        testStdOut.clear();
    }
    
    private void printTestMethodResult(final String status) {
      if (printTestMethodStatuses 
          && consoleReporter != null) {
        consoleReporter.writeMessage(" " + status);
      }
    }

    public void testSetCompleted( ReportEntry report )
    {
      testSetCompletedImpl(report, true);
    }
    
    private void testSetCompletedImpl(final ReportEntry report, final boolean checkCompleted) {
      if (checkCompleted && currentTestClassName == null) {
        System.out.println("ERROR: cannot mark test set completed while already completed.");
        return;
      }
      if (currentTestMethodName == null) {
        final WrappedReportEntry wrap = wrapTestSet( report, null );
        final List<String> testResults = briefOrPlainFormat ? detailsForThis.getTestResults() : null;
        if ( consoleReporter != null )
        {
            if (printTestMethodStatuses) {
              consoleReporter.writeLnMessage(" |");
            }
            consoleReporter.testSetCompleted( wrap, detailsForThis, testResults );
        }
        consoleOutputReceiver.testSetCompleted( wrap );
        if ( fileReporter != null )
        {
            fileReporter.testSetCompleted( wrap, detailsForThis, testResults );
        }
        if ( simpleXMLReporter != null )
        {
            simpleXMLReporter.testSetCompleted( wrap, detailsForThis );
        }
        if ( statisticsReporter != null )
        {
            statisticsReporter.testSetCompleted();
        }
        if ( consoleReporter != null )
        {
            consoleReporter.reset();
        }

        globalStatistics.add( detailsForThis );
        detailsForThis.reset();
      } else {
        System.out.println("ERROR: some test cases still did not finish, cannot mark test set completed.");
      }
      currentTestClassName = null;
    }

    // ----------------------------------------------------------------------
    // Test
    // ----------------------------------------------------------------------

    public void testStarting( final ReportEntry report )
    {
      if (currentTestClassName == null) {
        System.out.println("ERROR: a new test is starting while test set is not running.");
        return;
      }
      if (startTestImpl(report)) {
        detailsForThis.testStart();
      } else {
        System.out.println("ERROR: failed to start test ["+report+"] because another test is still running.");
      }
    }
    
    private boolean startTestImpl(final ReportEntry report) {
        final String key = getTestKey(report);
        final String currMethod = currentTestMethodName;
        if (currMethod == null) {
          currentTestMethodName = key;
          return true;
        } else {
          return false;
        }
    }

    public void testSucceeded( final ReportEntry reportEntry )
    {
      if (finishTestImpl(reportEntry, true)) {
        WrappedReportEntry wrapped = wrap( reportEntry, ReportEntryType.success );
        detailsForThis.testSucceeded( wrapped );
        if ( statisticsReporter != null )
        {
            statisticsReporter.testSucceeded( reportEntry );
        }
        printTestMethodResult(MARK_TEST_PASSED);
        clearCapture();
      } else {
        System.out.println("ERROR: failed to succeed test ["+reportEntry+"].");
      }
    }

    public void testError( ReportEntry reportEntry )
    {
      testErrorImpl(reportEntry, true, MARK_TEST_ERROR);
    }
    
    private boolean finishTestImpl( final ReportEntry entry, boolean check ) {
      if (check && currentTestClassName == null) {
        System.out.println("ERROR: failed to finish test ["+entry+"] because the test set is not running.");
        return false;
      }
      final String key = getTestKey(entry);
      final String c = currentTestMethodName;
      currentTestMethodName = null;
      if (!check) {
        return true;
      }
      return (c == null /*NB: skipped test may not be started.*/|| c.equals(key));  
    }
    
    private void testErrorImpl(ReportEntry reportEntry, boolean check, final String testMethodStatus) {
      if (finishTestImpl(reportEntry, check)) {
        final WrappedReportEntry wrapped = wrap( reportEntry, ReportEntryType.error );
        detailsForThis.testError( wrapped );
        if ( statisticsReporter != null )
        {
            statisticsReporter.testError( reportEntry );
        }
        globalStatistics.addErrorSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
        printTestMethodResult(testMethodStatus);
        clearCapture();
      } else {
        System.out.println("ERROR: failed to error test ["+reportEntry+"].");
      }
    }

    public void testFailed( final ReportEntry reportEntry )
    {
      if (finishTestImpl(reportEntry, true)) {
        WrappedReportEntry wrapped = wrap( reportEntry, ReportEntryType.failure );
        detailsForThis.testFailure( wrapped );
        if ( statisticsReporter != null )
        {
          statisticsReporter.testFailed( reportEntry );
        }
        globalStatistics.addFailureSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
        printTestMethodResult(MARK_TEST_FAILED);
        clearCapture();
      } else {
        System.out.println("ERROR: failed to fail test ["+reportEntry+"].");
      }
    }

    public void testSkipped( final ReportEntry reportEntry )
    {
      if (finishTestImpl(reportEntry, true)) {
        WrappedReportEntry wrapped = wrap( reportEntry, ReportEntryType.skipped );
        detailsForThis.testSkipped( wrapped );
        if ( statisticsReporter != null )
        {
            statisticsReporter.testSkipped( reportEntry );
        }
        globalStatistics.addSkippedSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
        printTestMethodResult(MARK_TEST_SKIPPED);
        clearCapture();
      } else {
        System.out.println("ERROR: failed to skip test ["+reportEntry+"].");
      }
    }

    public void testAssumptionFailure( ReportEntry report )
    {
        testSkipped( report );
    }

    // =====================================================================
    
    private String getAsString( List<ByteBuffer> byteBufferList )
    {
        StringBuilder stringBuffer = new StringBuilder();
        // To avoid getting a java.util.ConcurrentModificationException while iterating (see SUREFIRE-879) we need to
        // iterate over a copy or the elements array. Since the passed in byteBufferList is always wrapped with
        // Collections.synchronizedList( ) we are guaranteed toArray() is going to be atomic, so we are safe.
        for ( Object byteBuffer : byteBufferList.toArray() )
        {
            stringBuffer.append( byteBuffer.toString() );
        }
        return stringBuffer.toString();
    }

    private WrappedReportEntry wrap( ReportEntry other, ReportEntryType reportEntryType )
    {
        return new WrappedReportEntry( other, reportEntryType, other.getElapsed() != null
            ? other.getElapsed()
            : detailsForThis.getElapsedSinceLastStart(), getAsString( testStdOut ), getAsString( testStdErr ) );
    }

    private WrappedReportEntry wrapTestSet( ReportEntry other, ReportEntryType reportEntryType )
    {
        return new WrappedReportEntry( other, reportEntryType, other.getElapsed() != null
            ? other.getElapsed()
            : detailsForThis.getElapsedSinceTestSetStart(), getAsString( testStdOut ), getAsString( testStdErr ) );
    }

    public void close()
    {
        if (consoleOutputReceiver != null) {
          consoleOutputReceiver.close();
        }
    }
    
    public void timeout(final Exception ex) {
      detailsForThis.setExecutionTimeout(true);
      finishWithErrorImpl(ex, MARK_TEST_EXECUTION_TIMEOUT);
    }

    /**
     * Indicates test *engine* execution error
     * @param message
     */
    public void failure(final Exception ex) {
      detailsForThis.setExecutionFailure(true);
      finishWithErrorImpl(ex, MARK_TEST_EXECUTION_ERROR);
    }
    
    /*
     * Used for failure and timeout cases
     */
    private void finishWithErrorImpl(final Exception exception, final String testMethodStatus) {
      if (currentTestClassName != null) {
        final String source;
        final String name;
        final String runningTest = currentTestMethodName; 
        if (runningTest != null) {
            int hashPos = runningTest.indexOf('#');
            source = runningTest.substring(0, hashPos);
            name = runningTest.substring(hashPos + 1);
        } else {
          System.out.println("WARN: the test set is errored while no test-case is being executed.");
          source = "" + currentTestClassName; // null protection
          name = "anUnknownTestMethod("+source+")";
        }
        final StackTraceWriter stw = new PojoStackTraceWriter(null, null, exception);
        final Integer elapsedMs = Integer.valueOf(detailsForThis.getElapsedSinceLastStart());
        final ReportEntry entry = new SimpleReportEntry(source, name, stw, elapsedMs);
        testErrorImpl(entry, false, testMethodStatus);
        testSetCompletedImpl(entry, false);
      } else {
        System.out.println("WARN: test set already timed out or completed.");
      }
    } 
    
    private String getTestKey(ReportEntry entry) {
      // FQN of the test case:
      return entry.getSourceName() + "#" + entry.getName();
    }
    
}