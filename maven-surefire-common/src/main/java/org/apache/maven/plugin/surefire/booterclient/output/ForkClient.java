package org.apache.maven.plugin.surefire.booterclient.output;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.maven.plugin.surefire.report.DefaultReporterFactory;
import org.apache.maven.plugin.surefire.report.TestSetRunListener;
import org.apache.maven.surefire.booter.ForkingRunListener;
import org.apache.maven.surefire.report.CategorizedReportEntry;
import org.apache.maven.surefire.report.ConsoleLogger;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.ReporterException;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.util.NestedRuntimeException;
import org.apache.maven.surefire.util.internal.StringUtils;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Knows how to reconstruct *all* the state transmitted over stdout by the forked process.
 *
 * @author Kristian Rosenvold
 */
public class ForkClient
    implements StreamConsumer
{
   /*
    * This message will be written ion the end of each test set output file.
    * The purpose is to ensure that the file is fully written, not truncated somehow.  
    */
    private static final byte[] finalMessageBytes = "===== END OF TEST SET OUTPUT =====".getBytes();
  
    private final ReporterFactory providerReporterFactory;

    private final Map<Integer, RunListener> testSetReporters =
        Collections.synchronizedMap( new HashMap<Integer, RunListener>() );

    private final Properties testVmSystemProperties;

    private volatile boolean saidGoodBye = false;

    public ForkClient( DefaultReporterFactory providerReporterFactory, Properties testVmSystemProperties )
    {
        this.providerReporterFactory = providerReporterFactory;
        this.testVmSystemProperties = testVmSystemProperties;
    }
    
    private RunListener lazyGetRunListener(final Integer channelNumber, boolean doLazyInit) {
      RunListener runListener = testSetReporters.get( channelNumber );
      if (runListener == null && doLazyInit)
      {
        runListener = providerReporterFactory.createReporter();
        testSetReporters.put( channelNumber, runListener );
      }
      return runListener;
    }

    public void consumeLine( final String s )
    {
        try
        {
            if ( s.length() == 0 )
            {
                return;
            }
            final byte operationId = (byte) s.charAt( 0 );
            int commma = s.indexOf( ",", 3 );
            if ( commma < 0 )
            {
                System.out.println( s );
                return;
            }
            final Integer channelNumber = Integer.parseInt( s.substring( 2, commma ), 16 );
            final int rest = s.indexOf( ",", commma );
            final String remaining = s.substring( rest + 1 );
            final RunListener runListener;
            if (operationId == ForkingRunListener.BOOTERCODE_BYE) {
              runListener = lazyGetRunListener(channelNumber, false);
            } else {
            	runListener = lazyGetRunListener(channelNumber, true);
            }
            switch ( operationId )
            {
                case ForkingRunListener.BOOTERCODE_TESTSET_STARTING:
                    getOrCreateReporter( channelNumber ).testSetStarting( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TESTSET_COMPLETED:
                    getOrCreateReporter( channelNumber ).testSetCompleted( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_STARTING:
                    getOrCreateReporter( channelNumber ).testStarting( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_SUCCEEDED:
                    getOrCreateReporter( channelNumber ).testSucceeded( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_FAILED:
                    getOrCreateReporter( channelNumber ).testFailed( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_SKIPPED:
                    getOrCreateReporter( channelNumber ).testSkipped( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_ERROR:
                    getOrCreateReporter( channelNumber ).testError( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_TEST_ASSUMPTIONFAILURE:
                    getOrCreateReporter( channelNumber ).testAssumptionFailure( createReportEntry( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_SYSPROPS:
                    int keyEnd = remaining.indexOf( "," );
                    StringWriter key = new StringWriter();
                    StringWriter value = new StringWriter();
                    StringUtils.unescapeJava( key, remaining.substring( 0, keyEnd ) );
                    StringUtils.unescapeJava( value, remaining.substring( keyEnd + 1 ) );

                    synchronized ( testVmSystemProperties )
                    {
                        testVmSystemProperties.put( key, value );
                    }
                    break;
                case ForkingRunListener.BOOTERCODE_STDOUT:
                    byte[] bytes = new byte[remaining.length() * 2];
                    int len = StringUtils.unescapeJava( bytes, remaining );
                    getOrCreateConsoleOutputReceiver( channelNumber ).writeTestOutput( bytes, 0, len, true );
                    break;
                case ForkingRunListener.BOOTERCODE_STDERR:
                    bytes = new byte[remaining.length() * 2];
                    len = StringUtils.unescapeJava( bytes, remaining );
                    getOrCreateConsoleOutputReceiver( channelNumber ).writeTestOutput( bytes, 0, len, false );
                    break;
                case ForkingRunListener.BOOTERCODE_CONSOLE:
                    getOrCreateConsoleLogger( channelNumber ).info( createConsoleMessage( remaining ) );
                    break;
                case ForkingRunListener.BOOTERCODE_BYE:
                    saidGoodBye = true;
                    break;
                default:
                    System.out.println( s );
            }
        }
        catch ( NumberFormatException e )
        {
            System.out.println( s );
        }
        catch ( ReporterException e )
        {
            throw new NestedRuntimeException( e );
        }
    }

    public void consumeMultiLineContent( String s )
        throws IOException
    {
        BufferedReader stringReader = new BufferedReader( new StringReader( s ) );
        String s1;
        while ( ( s1 = stringReader.readLine() ) != null )
        {
            consumeLine( s1 );
        }
    }

    private String createConsoleMessage( String remaining )
    {
        return unescape( remaining );
    }

    private ReportEntry createReportEntry( final String untokenized )
    {
        StringTokenizer tokens = new StringTokenizer( untokenized, "," );
        try
        {
            String source = nullableCsv( tokens.nextToken() );
            String name = nullableCsv( tokens.nextToken() );
            String group = nullableCsv( tokens.nextToken() );
            String message = nullableCsv( tokens.nextToken() );
            String elapsedStr = tokens.nextToken();
            Integer elapsed = "null".equals( elapsedStr ) ? null : Integer.decode( elapsedStr );
            final StackTraceWriter stackTraceWriter =
                tokens.hasMoreTokens() ? deserializeStackStraceWriter( tokens ) : null;

            return CategorizedReportEntry.reportEntry( source, name, group, stackTraceWriter, elapsed, message );
        }
        catch ( RuntimeException e )
        {
            throw new RuntimeException( untokenized, e );
        }
    }

    private StackTraceWriter deserializeStackStraceWriter( StringTokenizer tokens )
    {
        StackTraceWriter stackTraceWriter;
        String stackTraceMessage = nullableCsv( tokens.nextToken() );
        String stackTrace = tokens.hasMoreTokens() ? nullableCsv( tokens.nextToken() ) : null;
        stackTraceWriter =
            stackTrace != null ? new DeserializedStacktraceWriter( stackTraceMessage, stackTrace ) : null;
        return stackTraceWriter;
    }

    private String nullableCsv( String source )
    {
        if ( "null".equals( source ) )
        {
            return null;
        }
        return unescape( source );
    }

    private String unescape( String source )
    {
        StringWriter stringWriter = new StringWriter( source.length() );

        StringUtils.unescapeJava( stringWriter, source );
        return stringWriter.getBuffer().toString();
    }

    /**
     * Used when getting reporters on the plugin side of a fork.
     *
     * @param channelNumber The logical channel number
     * @return A mock provider reporter
     */
    public RunListener getReporter( Integer channelNumber )
    {
        return testSetReporters.get( channelNumber );
    }

    private RunListener getOrCreateReporter( Integer channelNumber )
    {
        RunListener reporter = testSetReporters.get( channelNumber );
        if ( reporter == null )
        {
            reporter = providerReporterFactory.createReporter();
            testSetReporters.put( channelNumber, reporter );
        }
        return reporter;
    }

    private ConsoleOutputReceiver getOrCreateConsoleOutputReceiver( Integer channelNumber )
    {
        return (ConsoleOutputReceiver) getOrCreateReporter( channelNumber );
    }

    private ConsoleLogger getOrCreateConsoleLogger( Integer channelNumber )
    {
        return (ConsoleLogger) getOrCreateReporter( channelNumber );
    }

    public boolean isCorrectlyFinished() {
      return saidGoodBye;
    } 
    
    public void close()
    {
      // 1. should signal all the reporters to close the test output:
      final Collection<RunListener> runListeners = testSetReporters.values();
      for (RunListener rl: runListeners) {
        if (rl instanceof TestSetRunListener) {
          // XXX: casting.  
          ((TestSetRunListener)rl).writeTestOutput(finalMessageBytes, 0, finalMessageBytes.length, true/*stdout*/);
          // NB: close the output streams: 
          ((TestSetRunListener)rl).close();
        }
      }
      // cleanup the listeners: this will avoid 2nd writing in case #close() invoked several times.
      testSetReporters.clear(); 
    }
    
    /**
     * Requests all the run listeners to close the run sessions and mark the lest tests as timed out: 
     * @param seconds 
     */
    public void timeout(final Exception exception) {
    	final Collection<RunListener> runListeners = testSetReporters.values();
    	for (RunListener rl: runListeners) {
    		// XXX: casting.  
    		if (rl instanceof TestSetRunListener) {
    			((TestSetRunListener)rl).timeout(exception);
    		}
    	}
    }
    
    public void failure(final Exception ex) {
      final Collection<RunListener> runListeners = testSetReporters.values();
      for (RunListener rl: runListeners) {
        // XXX: casting.  
        if (rl instanceof TestSetRunListener) {
          ((TestSetRunListener)rl).failure(ex);
        }
      }
    } 
}
