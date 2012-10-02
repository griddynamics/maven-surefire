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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.plugin.surefire.runorder.StatisticsReporter;
import org.apache.maven.surefire.report.ConsoleLogger;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.PojoStackTraceWriter;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.RunStatistics;
import org.apache.maven.surefire.report.SimpleReportEntry;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.report.TestSetStatistics;
import org.apache.maven.surefire.util.internal.ByteBuffer;

/**
 * Reports data for a single test set.
 * <p/>
 */
public class TestSetRunListener
    implements RunListener, Reporter, ConsoleOutputReceiver,
    ConsoleLogger     // todo: Does this have to be a reporter ?
{
    
    private static final String EMPTY = ""; 
    
    private final AtomicBoolean testSetIsRunning = new AtomicBoolean(false);
    
    private final ConcurrentMap<String,String> testCaseMap = new ConcurrentHashMap<String,String>(2);
    
    private volatile String currentTestClassName;
    
    private final TestSetStatistics testSetStatistics;

    private final RunStatistics globalStatistics;

    private final MulticastingReporter multicastingReporter;

    private final List<ByteBuffer> testStdOut = Collections.synchronizedList( new ArrayList<ByteBuffer>() );

    private final List<ByteBuffer> testStdErr = Collections.synchronizedList( new ArrayList<ByteBuffer>() );


    public TestSetRunListener( AbstractConsoleReporter consoleReporter, AbstractFileReporter fileReporter,
                               XMLReporter xmlReporter, Reporter reporter, StatisticsReporter statisticsReporter,
                               RunStatistics globalStats )
    {

        List<Reporter> reporters = new ArrayList<Reporter>();
        if ( consoleReporter != null )
        {
            reporters.add( consoleReporter );
        }
        if ( fileReporter != null )
        {
            reporters.add( fileReporter );
        }
        if ( xmlReporter != null )
        {
            reporters.add( xmlReporter );
        }
        if ( reporter != null )
        {
            reporters.add( reporter );
        }
        if ( statisticsReporter != null )
        {
            reporters.add( statisticsReporter );
        }
        multicastingReporter = new MulticastingReporter( reporters );
        this.testSetStatistics = new TestSetStatistics();
        this.globalStatistics = globalStats;
    }

    public void info( String message )
    {
        multicastingReporter.writeMessage( message );
    }

    public void writeMessage( String message )
    {
        info( message );
    }

    public void writeMessage( byte[] b, int off, int len )
    {
        multicastingReporter.writeMessage( b, off, len );
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
        multicastingReporter.writeMessage( buf, off, len );
    }

    public synchronized void testSetStarting( final ReportEntry report )
    {
    	if (testSetIsRunning.compareAndSet(false, true)) {
    		currentTestClassName = report.getName();
    		multicastingReporter.testSetStarting( report );
    		if (!testCaseMap.isEmpty()) {
    			System.out.println("ERROR: some test cases still did not finish, and a new test set is starting.");
    		}
    		testCaseMap.clear();
    	} else {
    		System.out.println("ERROR: a new test set is starting while a previous one is not completed.");
    	}
    }

    public void clearCapture()
    {
        testStdErr.clear();
        testStdOut.clear();
    }

    public synchronized void testSetCompleted( ReportEntry report )
    {
    	testSetCompletedImpl(report, true);
    }
    
    private void testSetCompletedImpl(final ReportEntry report, final boolean checkCompleted) {
    	if (checkCompleted && !testSetIsRunning.compareAndSet(true, false)) {
    		System.out.println("ERROR: cannot mark test set completed while already completed.");
    		return;
    	}
    	if (testCaseMap.isEmpty()) {
    		multicastingReporter.testSetCompleted( report );
    		multicastingReporter.reset();
    		globalStatistics.add( testSetStatistics );
    		testSetStatistics.reset();
    		//System.out.println("}}} test set completed: " + currentTestClassName);
        } else {
        	System.out.println("ERROR: some test cases still did not finish, cannot mark test set completed.");
        }
    	currentTestClassName = null;
    }

    // ----------------------------------------------------------------------
    // Test
    // ----------------------------------------------------------------------

    public synchronized void testStarting( final ReportEntry report )
    {
    	if (!testSetIsRunning.get()) {
    		System.out.println("ERROR: a new test is starting while test set is not running.");
    		return;
    	}
    	if (startTestImpl(report)) {
    		multicastingReporter.testStarting( report );
    	} else {
    		System.out.println("ERROR: failed to mark starting test ["+report+"].");
    	}
    }
    
    private boolean startTestImpl(final ReportEntry report) {
        final String key = getTestKey(report);
        String pushed = testCaseMap.putIfAbsent(key, EMPTY);
        return (pushed == null);
    }

    private boolean finishTestImpl( final ReportEntry entry, boolean check ) {
    	if (check && !testSetIsRunning.get()) {
    		System.out.println("ERROR: failed to finish test ["+entry+"] because the test set is not running.");
    		return false;
    	}
        final String key = getTestKey(entry);
        String v = testCaseMap.remove(key);
        return true; //(v == EMPTY); skipped test may not be started.
    }
    
    public synchronized void testSucceeded( ReportEntry report )
    {
    	if (finishTestImpl(report, true)) {
    		testSetStatistics.incrementCompletedCount();
    		multicastingReporter.testSucceeded( report );
    		clearCapture();
    	} else {
    		System.out.println("ERROR: failed to succeed test ["+report+"].");
    	}
    }

    public synchronized void testError( ReportEntry reportEntry )
    {
    	testErrorImpl(reportEntry, true);
    }
    
    private void testErrorImpl(ReportEntry reportEntry, boolean check) {
    	if (finishTestImpl(reportEntry, check)) {
    		multicastingReporter.testError( reportEntry, getAsString( testStdOut ), getAsString( testStdErr ) );
    		testSetStatistics.incrementErrorsCount();
        	testSetStatistics.incrementCompletedCount();
        	globalStatistics.addErrorSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
        	clearCapture();
    	} else {
    		System.out.println("ERROR: failed to error test ["+reportEntry+"].");
    	}
    }

    public synchronized void testError( ReportEntry reportEntry, String stdOutLog, String stdErrLog )
    {
    	if (finishTestImpl(reportEntry, true)) {
    		multicastingReporter.testError( reportEntry, stdOutLog, stdErrLog );
    		testSetStatistics.incrementErrorsCount();
    		testSetStatistics.incrementCompletedCount();
    		globalStatistics.addErrorSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
    		clearCapture();
    	} else {
    		System.out.println("ERROR: failed to error test ["+reportEntry+"].");
    	}
    }

    public synchronized void testFailed( ReportEntry reportEntry )
    {
    	if (finishTestImpl(reportEntry, true)) {
    		multicastingReporter.testFailed( reportEntry, getAsString( testStdOut ), getAsString( testStdErr ) );
    		testSetStatistics.incrementFailureCount();
    		testSetStatistics.incrementCompletedCount();
    		globalStatistics.addFailureSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
    		clearCapture();
    	} else {
    		System.out.println("ERROR: failed to fail test ["+reportEntry+"].");
    	}
    }

    public synchronized void testFailed( ReportEntry reportEntry, String stdOutLog, String stdErrLog )
    {
    	if (finishTestImpl(reportEntry, true)) {
    		multicastingReporter.testFailed( reportEntry, stdOutLog, stdErrLog );
    		testSetStatistics.incrementFailureCount();
    		testSetStatistics.incrementCompletedCount();
    		globalStatistics.addFailureSource( reportEntry.getName(), reportEntry.getStackTraceWriter() );
    		clearCapture();
    	} else {
    		System.out.println("ERROR: failed to fail test ["+reportEntry+"].");
    	}
    }

    // ----------------------------------------------------------------------
    // Counters
    // ----------------------------------------------------------------------

    public synchronized void testSkipped( ReportEntry report )
    {
    	if (finishTestImpl(report, true)) {
         multicastingReporter.testSkipped( report );
         testSetStatistics.incrementSkippedCount();
         testSetStatistics.incrementCompletedCount();
         globalStatistics.addSkippedSource( report.getName(), report.getStackTraceWriter());
    		clearCapture();
    	} else {
    		System.out.println("ERROR: failed to skip test ["+report+"].");
    	}
    }

    public void testAssumptionFailure( ReportEntry report )
    {
        testSkipped( report );
    }

    public synchronized void reset()
    {
    	testSetIsRunning.set(false);
    	currentTestClassName = null;
        multicastingReporter.reset();
        testCaseMap.clear();
    }

    public String getAsString( List<ByteBuffer> byteBufferList )
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
    
    public synchronized void timeout(final int timeoutSeconds) {
    	if (testSetIsRunning.compareAndSet(true, false)) {
    		//System.out.println(this + " ====== timing out....");
    		final Iterator<Entry<String,String>> it = testCaseMap.entrySet().iterator();
    		final String source;
    		final String name;
    		if (it.hasNext()) {
        		final Entry<String,String> e = it.next();
//        		if (it.hasNext()) {
//        			throw new AssertionError("More than 1 test is running.");
//        		}
        		final String runningTest = e.getKey();
        		int hashPos = runningTest.indexOf('#');
        		source = runningTest.substring(0, hashPos);
        		name = runningTest.substring(hashPos + 1);
    		} else {
    			System.out.println("WARN: the test set is timed out while no test-case is being executed.");
    			source = "" + currentTestClassName; // null protection
    			name = "anUnknownTestMethod("+source+")";
    			//throw new AssertionError("No test is running -- nothing to timeout.");
    		}
    		final String message = "Test timed out after "+timeoutSeconds+" seconds.";
    		final StackTraceWriter stw = new PojoStackTraceWriter(null, null, new TimeoutException(message));
    		final ReportEntry entry = new SimpleReportEntry(source, name, stw);
    		testErrorImpl(entry, false);
    		// ReportEntry entry2 = new SimpleReportEntry(source, name, "Test case ["+runningTest+"] timed out after "+timeoutSeconds+" seconds. ");
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
