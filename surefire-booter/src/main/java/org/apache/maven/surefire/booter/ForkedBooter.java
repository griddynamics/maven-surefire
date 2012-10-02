package org.apache.maven.surefire.booter;

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
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.Permission;

import org.apache.maven.surefire.suite.RunResult;

/**
 * The part of the booter that is unique to a forked vm.
 * <p/>
 * Deals with deserialization of the booter wire-level protocol
 * <p/>
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Kristian Rosenvold
 * @version $Id$
 */
public class ForkedBooter
{

    /**
     * This method is invoked when Surefire is forked - this method parses and organizes the arguments passed to it and
     * then calls the Surefire class' run method. <p/> The system exit code will be 1 if an exception is thrown.
     *
     * @param args Commandline arguments
     * @throws Throwable Upon throwables
     */
    public static void main( final String[] args )
        throws Throwable
    {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        
    	final SecurityManager originalSecurityManager = System.getSecurityManager();
        try
        {
        	final SecurityManager checkExitSecurityManager = new DelegatingSecurityManager(originalSecurityManager) {
        		//@Override NB: source level
        		public void checkExit(final int status) {
        			originalErr.println("The following exception will be thrown right now:");
        			final RuntimeException re = new SecurityException("ERROR: an attempted detected to perform JVM exit/halt with code ["+status+"]. " +
            				"Invocation of System#exit(), Runtime#exit(), or Runtime#halt() is not allowed in tests.");
        			re.printStackTrace(originalErr);
        			throw re;
        		}
//        		//@Override NB: source level
//        		public void checkPermission(Permission perm) {
//        			// NB: forbid also to work with the shutdown hooks:
//        		    // NB: we cannot forbid shutdown hooks since java.util.logging.LogManager.<init>(LogManager.java:236) adds a shutdown hook. 
//        			if (perm != null && "shutdownHooks".equals(perm.getName())) {
//            			originalErr.println("The following exception will be thrown right now:");
//            			final RuntimeException re = new SecurityException("ERROR: an attempted detected to add or remove a shutdown hook. This is not allowed in tests.");
//            			re.printStackTrace(originalErr);
//            			throw re;
//        			} 
//        			super.checkPermission(perm);
//        		}
        	};
        	System.setSecurityManager(checkExitSecurityManager);
        	// ---------------------
        	
            if ( args.length > 1 )
            {
                SystemPropertyManager.setSystemProperties( new File( args[1] ) );
            }

            File surefirePropertiesFile = new File( args[0] );
            InputStream stream = surefirePropertiesFile.exists() ? new FileInputStream( surefirePropertiesFile ) : null;
            BooterDeserializer booterDeserializer = new BooterDeserializer( stream );
            ProviderConfiguration providerConfiguration = booterDeserializer.deserialize();
            final StartupConfiguration startupConfiguration = booterDeserializer.getProviderConfiguration();

            TypeEncodedValue forkedTestSet = providerConfiguration.getTestForFork();

            final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            final ClassLoader testClassLoader = classpathConfiguration.createForkingTestClassLoader(
                startupConfiguration.isManifestOnlyJarRequestedAndUsable() );

            startupConfiguration.writeSurefireTestClasspathProperty();

            Object testSet = forkedTestSet != null ? forkedTestSet.getDecodedValue( testClassLoader ) : null;
            runSuitesInProcess( testSet, testClassLoader, startupConfiguration, providerConfiguration );
            // Say bye.
            originalOut.println( "Z,0,BYE!" );
            originalOut.flush();
            
            // noinspection CallToSystemExit
            System.setSecurityManager(originalSecurityManager);
            System.exit( 0 );
        }
        catch ( Throwable t )
        {
            // Just throwing does getMessage() and a local trace - we want to call printStackTrace for a full trace
            // noinspection UseOfSystemOutOrSystemErr
        	originalErr.println("########################## "+ForkedBooter.class.getName()+": FATAL: A throwable caught:");
            t.printStackTrace( originalErr );
            originalErr.flush();
            // noinspection ProhibitedExceptionThrown,CallToSystemExit
            final int exitCode = 77;
        	originalErr.println("########################## "+ForkedBooter.class.getName()+": Terminating the forked JVM with status ["+exitCode+"].");
            originalErr.flush();
            
            System.setSecurityManager(originalSecurityManager);
            System.exit( exitCode );
        }
    }

    public static RunResult runSuitesInProcess( Object testSet, ClassLoader testsClassLoader,
                                                StartupConfiguration startupConfiguration,
                                                ProviderConfiguration providerConfiguration )
        throws SurefireExecutionException
    {
        final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
        ClassLoader surefireClassLoader = classpathConfiguration.createSurefireClassLoader( testsClassLoader );

        SurefireReflector surefireReflector = new SurefireReflector( surefireClassLoader );

        final Object factory = createForkingReporterFactory( surefireReflector, providerConfiguration );

        return ProviderFactory.invokeProvider( testSet, testsClassLoader, surefireClassLoader, factory,
                                               providerConfiguration, true, startupConfiguration );
    }

    private static Object createForkingReporterFactory( SurefireReflector surefireReflector,
                                                        ProviderConfiguration providerConfiguration )
    {
        final Boolean trimStackTrace = providerConfiguration.getReporterConfiguration().isTrimStackTrace();
        final PrintStream originalSystemOut = providerConfiguration.getReporterConfiguration().getOriginalSystemOut();
        return surefireReflector.createForkingReporterFactory( trimStackTrace, originalSystemOut );
    }
    
    private static class DelegatingSecurityManager extends SecurityManager {
    	private final SecurityManager delegate; 
    	public DelegatingSecurityManager(SecurityManager sm) {
			delegate = sm;
		}
		public void checkPermission(Permission perm) {
			if (delegate != null) {
				delegate.checkPermission(perm);
			}
		}
		public void checkPermission(Permission perm, Object context) {
			if (delegate != null) {
				delegate.checkPermission(perm, context);
			}
		}
    }
}
