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
import org.apache.maven.plugin.surefire.SurefireProperties;
import org.apache.maven.plugin.surefire.StartupReportConfiguration;
import org.apache.maven.plugin.surefire.booterclient.output.ForkClient;
import org.apache.maven.plugin.surefire.booterclient.output.ThreadedStreamConsumer;
import org.apache.maven.plugin.surefire.report.DefaultReporterFactory;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.ClasspathConfiguration;
import org.apache.maven.surefire.booter.KeyValueSource;
import org.apache.maven.surefire.booter.PropertiesWrapper;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.ProviderFactory;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.booter.SurefireBooterForkException;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.booter.SystemPropertyManager;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.RunStatistics;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.util.DefaultScanResult;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineTimeOutException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;


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
 * @version $Id: ForkStarter.java 1236422 2012-01-26 22:37:06Z krosenvold $
 */
public class ForkStarter
{
  /**
   * Property that indicates that the test run should be stopped upon first execution failure.
   * ("Failure" above means not a test failure, but a test execution failure, which typically 
   * means some abnormal exit of a test or an error of the test engine itself.)
   * This value is 'false' by default;
   * The value can be re-defined by passing the property to Maven executable via the -D key.  
   */
  private static final boolean stopOnExecutionFailure 
    = getBooleanSystemProperty("stop-on-execution-failure", false);
  /**
   * Property that indicates that an abnormal termination of a forked test process is to be treated only 
   * as the test error. Abnormal termination is either non-zero exit code of the forked process, or  
   * (in case of zero exit code) incorrect termination without corresponding closing message.   
   * 'true' value means that the the last executed test just receives Error status with appropriate message;
   * 'false' value means that the last executed test receives Error status with appropriate message, plus
   *   the problem is reported as an execution error.   
   * The value can be re-defined by passing the property to Maven executable via the -D key.  
   */
  private static final boolean treatAbnormalForkedProcessExitAsTestErrorOnly 
    = getBooleanSystemProperty("treat-abnormal-forked-process-exit-as-test-error-only", true);
  
  public static boolean getBooleanSystemProperty(final String propertyName, final boolean defaultValue) {
    final String value = System.getProperty(propertyName);
    if (value == null) {
      return defaultValue;
    } else {
      return Boolean.parseBoolean(value);
    } 
  } 
  
    private final int forkedProcessTimeoutInSeconds;

    private final ProviderConfiguration providerConfiguration;

    private final StartupConfiguration startupConfiguration;

    private final ForkConfiguration forkConfiguration;

    private final StartupReportConfiguration startupReportConfiguration;

    private final DefaultReporterFactory fileReporterFactory;

    private static volatile int systemPropertiesFileCounter = 0;

    public ForkStarter( ProviderConfiguration providerConfiguration, StartupConfiguration startupConfiguration,
                        ForkConfiguration forkConfiguration, int forkedProcessTimeoutInSeconds,
                        StartupReportConfiguration startupReportConfiguration )
    {
        this.forkConfiguration = forkConfiguration;
        this.providerConfiguration = providerConfiguration;
        this.forkedProcessTimeoutInSeconds = forkedProcessTimeoutInSeconds;
        this.startupConfiguration = startupConfiguration;
        this.startupReportConfiguration = startupReportConfiguration;
        fileReporterFactory = new DefaultReporterFactory( startupReportConfiguration );
    }

    public RunResult run( SurefireProperties effectiveSystemProperties, DefaultScanResult scanResult, String requestedForkMode )
        throws SurefireBooterForkException, SurefireExecutionException
    {
        final RunResult result;
        try
        {
            Properties providerProperties = providerConfiguration.getProviderProperties();
            scanResult.writeTo( providerProperties );
            if ( ForkConfiguration.FORK_ONCE.equals( requestedForkMode ) )
            {
                final ForkClient forkClient =
                    new ForkClient( fileReporterFactory, startupReportConfiguration.getTestVmSystemProperties() );
                result = fork( null, new PropertiesWrapper( providerProperties), forkClient, fileReporterFactory.getGlobalRunStatistics(),
                               effectiveSystemProperties );
            }
            else if ( ForkConfiguration.FORK_ALWAYS.equals( requestedForkMode ) )
            {
                result = runSuitesForkPerTestSet( providerProperties, effectiveSystemProperties, 1 );
            }
            else if ( ForkConfiguration.FORK_PERTHREAD.equals( requestedForkMode ) )
            {
                result = runSuitesForkPerTestSet( providerProperties, effectiveSystemProperties, forkConfiguration.getForkCount() );
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

    private RunResult runSuitesForkPerTestSet( final Properties properties, 
                                               final SurefireProperties effectiveSystemProperties, final int forkCount )
        throws SurefireBooterForkException
    {

        final ArrayList<Future<RunResult>> resultFutures = new ArrayList<Future<RunResult>>( 500 );
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
                	//@Override NB: source=1.5
                    public RunResult call()
                        throws Exception
                    {
                        return fork( testSet, new PropertiesWrapper( properties), forkClient,
                                     fileReporterFactory.getGlobalRunStatistics(),
                                     effectiveSystemProperties );
                    }
                };
                Future<RunResult> future = executorService.submit( pf );
                if (future == null) {
                	throw new AssertionError("Future cannot be null.");
                }
                resultFutures.add(future);
            }

            System.out.println("########### " + resultFutures.size() + " test set tasks submitted for execution." );
            int obtainedResultCount = 0;
            for ( final Future<RunResult> resultFuture: resultFutures )
            {
                try
                {
                    final RunResult current = resultFuture.get();
                    if ( current != null )
                    {
                      obtainedResultCount++;
                      System.out.println("##### Results so far: #"+obtainedResultCount+": " + toDebugString(current) );
                      globalResult = current;
                    }
                    else
                    {
                      throw new SurefireBooterForkException( "No results for " + resultFuture.toString() );
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
        	System.out.println("############################################### ERROR: ");
        	t.printStackTrace(System.out); // log the error -- make it visible in the log for sure.
        	if (stopOnExecutionFailure) {
        	  // cancel all the remaining tasks:
        	  System.out.println("Forcibly shutting down the executor service...");
        	  final List<Runnable> awaitingList = executorService.shutdownNow();
        	  if (awaitingList != null) {
        	    System.out.println("Cancelled execution of "+awaitingList.size()+" remaining test sets (+ possibly the current one).");
        	  }
        	}
        	throw new SurefireBooterForkException("Error while executing tests:", t);
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
        	//System.out.println("Waiting "+timeoutSec+" sec for the test executor service to finish the remaining tests...");
            final boolean closed = executorService.awaitTermination( timeoutSec, TimeUnit.SECONDS );
            if (!closed) {
            	throw new SurefireBooterForkException("ERROR: timed out closing the executor ("+timeoutSec+").");
            }
        }
        catch ( InterruptedException e )
        {
            throw new SurefireBooterForkException( "Interrupted", e );
        }
    }

    private RunResult fork( final Object testSet, final KeyValueSource providerProperties, final ForkClient forkClient,
                            final RunStatistics globalRunStatistics, final SurefireProperties effectiveSystemProperties )
        throws SurefireBooterForkException
    {
        File surefireProperties;
        File systPropsFile = null;
        try
        {
            BooterSerializer booterSerializer = new BooterSerializer( forkConfiguration );

            surefireProperties = booterSerializer.serialize( providerProperties, providerConfiguration, startupConfiguration, testSet );

            if ( effectiveSystemProperties != null )
            {
                systPropsFile = SystemPropertyManager.writePropertiesFile( effectiveSystemProperties,
                                                                           forkConfiguration.getTempDirectory(),
                                                                           "surefire_" + systemPropertiesFileCounter++,
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

        @SuppressWarnings( "unchecked" ) Commandline cli =
            forkConfiguration.createCommandLine( bootClasspath.getClassPath(),
                                                 startupConfiguration.getClassLoaderConfiguration(),
                                                 startupConfiguration.isShadefire() );

        cli.createArg().setFile( surefireProperties );

        if ( systPropsFile != null )
        {
            cli.createArg().setFile( systPropsFile );
        }

        final ThreadedStreamConsumer threadedStreamConsumer = new ThreadedStreamConsumer( forkClient );

        if ( forkConfiguration.isDebug() )
        {
            System.out.println( "Forking command line: " + cli );
        }

        RunResult runResult = null;
        try
        {
            final int timeout = (forkedProcessTimeoutInSeconds > 0) ? forkedProcessTimeoutInSeconds : 0;
            final int processExitCode =
                CommandLineUtils.executeCommandLine( cli, threadedStreamConsumer, threadedStreamConsumer, timeout );

            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            
            final String errorMessage;
            if (processExitCode == 0) {
              final boolean isCorrectlyFinished = forkClient.isCorrectlyFinished();
              if (isCorrectlyFinished) {
                errorMessage = null;
              } else {
                errorMessage = "The forked VM terminated with zero exit code, but without saying properly goodbye. VM crash or System.exit() called?";
              }
            } else {
              errorMessage = "Forked process exited with non-zero code = "+processExitCode;
            }
            
            if (errorMessage != null) {
              System.out.println("##### Test execution failure: ["+errorMessage+"]");
              // in this case mark the last executed test as Error:
              forkClient.failure(new RuntimeException(errorMessage));
            }
            
            // NB: the test console output will be finally closed there:
            forkClient.close();
            
            if (errorMessage != null && !treatAbnormalForkedProcessExitAsTestErrorOnly) {
              // report this failure as booter fork failure:
              throw new SurefireBooterForkException( errorMessage );
            }
            
            runResult = globalRunStatistics.getRunResult();
        }
        catch (final CommandLineTimeOutException e )
        { 
            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            // NB: ask the forkClient to complete the running test set and set appropriate statuses.
            // NB: the global statistics will be updated with the "timeout" status in this call:
            forkClient.timeout(e);
            forkClient.close();
            runResult = globalRunStatistics.getRunResult();
        }
        catch ( CommandLineException e )
        {
            // wait for the buffered streamer to put everything to the forkClient (actual consumer):
            threadedStreamConsumer.close();
            forkClient.failure(e); // indicate runner failure to the global statistics.
            forkClient.close(); // just a cleanup in this case
            // fail:
            throw new SurefireBooterForkException( "Error while executing forked tests.", e.getCause() );
        }
        finally
        {
            // NB: no problem if #close() in the 2 lines below will be executed 2nd time.
            threadedStreamConsumer.close();
            forkClient.close();
            if ( runResult == null )
            {
                runResult = globalRunStatistics.getRunResult();
            }
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
