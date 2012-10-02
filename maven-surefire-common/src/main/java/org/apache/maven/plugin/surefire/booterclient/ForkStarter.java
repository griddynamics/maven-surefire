package org.apache.maven.plugin.surefire.booterclient;

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.maven.plugin.surefire.CommonReflector;
import org.apache.maven.plugin.surefire.StartupReportConfiguration;
import org.apache.maven.plugin.surefire.booterclient.output.ForkClient;
import org.apache.maven.plugin.surefire.booterclient.output.ThreadedStreamConsumer;
import org.apache.maven.plugin.surefire.report.FileReporterFactory;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.ClasspathConfiguration;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.ProviderFactory;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.booter.SurefireBooterForkException;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.booter.SystemPropertyManager;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.RunStatistics;
import org.apache.maven.surefire.suite.RunResult;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineTimeOutException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.DefaultConsumer;


/**
 * Starts the fork or runs in-process.
 * <p/>
 * Lives only on the plugin-side (not present in remote vms)
 * <p/>
 * Knows how to fork new vms and also how to delegate non-forking invocation to SurefireStarter directly
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Brett Porter
 * @author Dan Fabulich
 * @author Carlos Sanchez
 * @author Kristian Rosenvold
 * @version $Id$
 */
public class ForkStarter
{
    private final int forkedProcessTimeoutInSeconds;

    private final ProviderConfiguration providerConfiguration;

    private final StartupConfiguration startupConfiguration;

    private final ForkConfiguration forkConfiguration;

    private final StartupReportConfiguration startupReportConfiguration;

    private final FileReporterFactory fileReporterFactory;

    private static volatile int systemPropertiesFileCounter = 0;

    //private static final Object lock = new Object();


    public ForkStarter( ProviderConfiguration providerConfiguration, StartupConfiguration startupConfiguration,
                        ForkConfiguration forkConfiguration, int forkedProcessTimeoutInSeconds,
                        StartupReportConfiguration startupReportConfiguration )
    {
        this.forkConfiguration = forkConfiguration;
        this.providerConfiguration = providerConfiguration;
        this.forkedProcessTimeoutInSeconds = forkedProcessTimeoutInSeconds;
        this.startupConfiguration = startupConfiguration;
        this.startupReportConfiguration = startupReportConfiguration;
        fileReporterFactory = new FileReporterFactory( startupReportConfiguration );
    }

    public RunResult run()
        throws SurefireBooterForkException, SurefireExecutionException
    {
        final RunResult result;
        final String requestedForkMode = forkConfiguration.getForkMode();
        try
        {
            if ( ForkConfiguration.FORK_ONCE.equals( requestedForkMode ) )
            {
                final ForkClient forkClient =
                    new ForkClient( fileReporterFactory, startupReportConfiguration.getTestVmSystemProperties() );
                result = fork( null, providerConfiguration.getProviderProperties(), forkClient,
                               fileReporterFactory.getGlobalRunStatistics() );
            }
            else if ( ForkConfiguration.FORK_ALWAYS.equals( requestedForkMode ) )
            {
                result = runSuitesForkPerTestSet( providerConfiguration.getProviderProperties(), 1 );
            }
            else if ( ForkConfiguration.FORK_PERTHREAD.equals( requestedForkMode ) )
            {
                result = runSuitesForkPerTestSet( providerConfiguration.getProviderProperties(),
                                                  forkConfiguration.getThreadCount() );
            }
            else
            {
                throw new SurefireExecutionException( "Unknown forkmode: " + requestedForkMode, null );
            }
        }
        finally
        {
            fileReporterFactory.close();
        }
        return result;
    }

    private RunResult runSuitesForkPerTestSet( final Properties properties, final int forkCount )
        throws SurefireBooterForkException
    {

        final ArrayList<Future<RunResult>> results = new ArrayList<Future<RunResult>>( 500 );
        final ExecutorService executorService = new ThreadPoolExecutor( forkCount, forkCount, 60, TimeUnit.SECONDS,
                                                                  new ArrayBlockingQueue<Runnable>( 500 ) );

        try
        {
            // Ask to the executorService to run all tasks
            RunResult globalResult = new RunResult( 0, 0, 0, 0 );
            final Iterator<Object> suites = getSuitesIterator();
            while ( suites.hasNext() )
            {
                final Object testSet = suites.next();
                final ForkClient forkClient =
                    new ForkClient( fileReporterFactory, startupReportConfiguration.getTestVmSystemProperties() );
                final Callable<RunResult> pf = new Callable<RunResult>()
                {
                	@Override
                    public RunResult call()
                        throws Exception
                    {
                        return fork( testSet, (Properties) properties.clone(), forkClient, 
                    	              fileReporterFactory.getGlobalRunStatistics() );
                    }
                };
                Future<RunResult> future = executorService.submit( pf );
                if (future == null) {
                	throw new AssertionError("Future cannot be null.");
                }
                results.add(future);
            }

            System.out.println("########### " + results.size() + " test set tasks submitted for execution." );
            int obtainedResultCount = 0;
            for ( final Future<RunResult> result: results )
            {
                try
                {
                    final RunResult current = result.get();
                    if ( current != null )
                    {
                    	obtainedResultCount++;
                        //System.out.println("# global    result: #"+obtainedResultCount+": " + toDebugString(globalResult) );
                        System.out.println("##### Results so far: #"+obtainedResultCount+": " + toDebugString(current) );
                        //globalResult = globalResult.aggregate( current );
                        globalResult = current;
                        //System.out.println("# global + current: #"+obtainedResultCount+": " + toDebugString(globalResult) );
                    }
                    else
                    {
                        throw new SurefireBooterForkException( "No results for " + result.toString() );
                    }
                }
                catch ( InterruptedException e )
                {
                    throw new SurefireBooterForkException( "Interrupted", e );
                }
                catch ( ExecutionException e )
                {
                    throw new SurefireBooterForkException( "ExecutionException", e );
                }
            }
            return globalResult;
        } catch (final Throwable t) {
        	System.out.println("############################################### FATAL ERROR: ");
        	t.printStackTrace(System.out); // log the error -- make it visible in the log for sure.
        	// cancel all the remaining tasks:
        	System.out.println("Forcibly shutting down the executor service...");
        	final List<Runnable> awaitingList = executorService.shutdownNow();
        	if (awaitingList != null) {
        		System.out.println("Cancelled execution of "+awaitingList.size()+" remaining test sets (+ possibly the current one).");
        	}
        	throw new SurefireBooterForkException("Fatal error while executing tests:", t);
        } finally {
        	// NB: this will wait for all the remaining tasks to complete:
            closeExecutor( executorService );
        }
    }

    public static String toDebugString(final RunResult rr) {
    	if (rr == null) {
    		return null;
    	}
    	String x = "completed="+rr.getCompletedCount() + ": errors=" + rr.getErrors() + ", failures=" + rr.getFailures() + ", skipped=" + rr.getSkipped() + ", isFailure=" + rr.isFailure()
                + ", isTimeout=" + rr.isTimeout();
    	return x;
    }
    
    private void closeExecutor( final ExecutorService executorService )
        throws SurefireBooterForkException
    {
        executorService.shutdown(); 
        try
        {
            // Should stop immediately, as we got all the results if we are here
        	final long timeoutSec = 60 * 60; // 1 hour
        	System.out.println("Waiting "+timeoutSec+" sec for the test executor service to finish the remaining tests...");
            final boolean closed = executorService.awaitTermination( timeoutSec, TimeUnit.SECONDS );
            if (closed) {
            	System.out.println("Executor closed.");
            } else {
            	throw new SurefireBooterForkException("ERROR: timed out closing the executor ("+timeoutSec+").");
            }
        }
        catch ( InterruptedException e )
        {
            throw new SurefireBooterForkException( "Interrupted", e );
        }
    }


    private RunResult fork( final Object testSet, final Properties properties, final ForkClient forkClient,
                            final RunStatistics globalRunStatistics )
        throws SurefireBooterForkException
    {
        File surefireProperties;
        File systemProperties = null;
        try
        {
            BooterSerializer booterSerializer = new BooterSerializer( forkConfiguration, properties );

            surefireProperties = booterSerializer.serialize( providerConfiguration, startupConfiguration, testSet,
                                                             forkConfiguration.getForkMode() );

            if ( forkConfiguration.getSystemProperties() != null )
            {
                systemProperties = SystemPropertyManager.writePropertiesFile( forkConfiguration.getSystemProperties(),
                                                                              forkConfiguration.getTempDirectory(),
                                                                              "surefire_"
                                                                                  + systemPropertiesFileCounter++,
                                                                              forkConfiguration.isDebug() );
            }
        }
        catch ( IOException e )
        {
            throw new SurefireBooterForkException( "Error creating properties files for forking", e );
        }

        final Classpath bootClasspathConfiguration = forkConfiguration.getBootClasspath();

        final Classpath additionlClassPathUrls = startupConfiguration.useSystemClassLoader()
            ? startupConfiguration.getClasspathConfiguration().getTestClasspath()
            : null;

        // Surefire-booter + all test classes if "useSystemClassloader"
        // Surefire-booter if !useSystemClassLoader
        Classpath bootClasspath = Classpath.join( bootClasspathConfiguration, additionlClassPathUrls );

        Commandline cli = forkConfiguration.createCommandLine( bootClasspath.getClassPath(),
                                                               startupConfiguration.getClassLoaderConfiguration(),
                                                               startupConfiguration.isShadefire() );

        cli.createArg().setFile( surefireProperties );

        if ( systemProperties != null )
        {
            cli.createArg().setFile( systemProperties );
        }

        final ThreadedStreamConsumer threadedStreamConsumer = new ThreadedStreamConsumer( forkClient );

        if ( forkConfiguration.isDebug() )
        {
            System.out.println( "Forking command line: " + cli );
        }

        RunResult runResult;
        try
        {
            final int timeout = (forkedProcessTimeoutInSeconds > 0) ? forkedProcessTimeoutInSeconds : 0;
            final int result =
                CommandLineUtils.executeCommandLine( cli, threadedStreamConsumer, threadedStreamConsumer, timeout );

            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            
            if ( result == RunResult.SUCCESS ) {
            	forkClient.close(true); // NB: require goodbye: exception thrown from there if no "BYE" message was sent by the ForkedBooter.
            } else {
            	forkClient.close(false);
                throw new SurefireBooterForkException( "Error occurred in starting fork, check output in the test log. Process exit code = "+result );
            }

            runResult = globalRunStatistics.getRunResult();
        }
        catch ( CommandLineTimeOutException e )
        { 
            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            // NB: ask the forkClient to complete the running test set and set appropriate statuses:
            forkClient.timeout(forkedProcessTimeoutInSeconds);
            globalRunStatistics.setTimeout(true); // NB: indicate that a timeout happened.
            forkClient.close(false);
            runResult = globalRunStatistics.getRunResult();
        }
        catch ( CommandLineException e )
        {
            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            forkClient.close(false); // just a cleanup in this case
            // fail:
            throw new SurefireBooterForkException( "Error while executing forked tests.", e.getCause() );
        }

        return runResult;
    }

    private Iterator getSuitesIterator()
        throws SurefireBooterForkException
    {
        try
        {
            final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            ClassLoader testsClassLoader = classpathConfiguration.createTestClassLoader( false );
            ClassLoader surefireClassLoader =
                classpathConfiguration.createInprocSurefireClassLoader( testsClassLoader );

            CommonReflector commonReflector = new CommonReflector( surefireClassLoader );
            Object reporterFactory = commonReflector.createReportingReporterFactory( startupReportConfiguration );

            final ProviderFactory providerFactory =
                new ProviderFactory( startupConfiguration, providerConfiguration, surefireClassLoader, testsClassLoader,
                                     reporterFactory );
            SurefireProvider surefireProvider = providerFactory.createProvider( false );
            return surefireProvider.getSuites();
        }
        catch ( SurefireExecutionException e )
        {
            throw new SurefireBooterForkException( "Unable to create classloader to find test suites", e );
        }
    }
    
}
