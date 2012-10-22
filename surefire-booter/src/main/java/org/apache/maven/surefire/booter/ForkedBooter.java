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

import org.apache.maven.surefire.report.ReporterFactory;
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

  public static boolean getBooleanSystemProperty(final String propertyName, final boolean defaultValue) {
    final String value = System.getProperty(propertyName);
    if (value == null) {
      return defaultValue;
    } else {
      return Boolean.parseBoolean(value);
    } 
  } 
  
  /**
   * Denies calls of {@link System#exit(int)}), {@link Runtime#exit(int)}), {@link Runtime#halt(int)} by installing corresponding {@link SecurityManager}.
   * If denied, a security exception will be logged and thrown instead of the actual method call.
   * NB: this System property is checked *before* the test System properties are read from the file and set,
   * so, it should be passed in pom.xml though <argLine> -D...=...</argLine> construct, but not through any other means.   
   */
  private static final boolean denySystemExit = getBooleanSystemProperty("security-deny-system-exit", false);

  private static ReporterFactory reporterFactory;
  
    /**
     * This method is invoked when Surefire is forked - this method parses and organizes the arguments passed to it and
     * then calls the Surefire class' run method. <p/> The system exit code will be 1 if an exception is thrown.
     *
     * @param args Commandline arguments
     * @throws Throwable Upon throwables
     */
    public static void main( final String[] args )
    {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        
        boolean customSecurityManagerSet = false;
    	  final SecurityManager originalSecurityManager = System.getSecurityManager();
        try
        {
          if (denySystemExit) {
            originalErr.println(" "+ForkedBooter.class.getName()+": denySystemExit = " + denySystemExit);
            // NB: we cannot forbid shutdown hooks since java.util.logging.LogManager.<init>(LogManager.java:236) adds a shutdown hook.
            final SecurityManager checkExitSecurityManager = new DelegatingSecurityManager(originalSecurityManager) {
              //@Override NB: source level
              public void checkExit(final int status) {
                originalErr.println("The following exception will be thrown right now:");
                final RuntimeException re = new SecurityException("ERROR: an attempted detected to perform JVM exit/halt with code ["+status+"]. " +
                    "Invocation of System#exit(), Runtime#exit(), or Runtime#halt() is not allowed in tests.");
                re.printStackTrace(originalErr);
                throw re;
              }
            };
            System.setSecurityManager(checkExitSecurityManager);
            customSecurityManagerSet = true;
          }
          
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

            final Object testSet = forkedTestSet != null ? forkedTestSet.getDecodedValue( testClassLoader ) : null;
            runSuitesInProcess( testSet, testClassLoader, startupConfiguration, providerConfiguration);
            
            final int[] channelIds = getChannelIds();
            if (channelIds != null && channelIds.length > 0) {
              for (int i=0; i<channelIds.length; i++) {
                sayBye(originalOut, channelIds[i]);
              }
            } else { 
              originalErr.println("WARN: no channel ID found to say good bye. As a fallback saying goodbye to channel 0.");
              sayBye(originalOut, 0);
            } 
            
            // noinspection CallToSystemExit
            if (customSecurityManagerSet) {
              System.setSecurityManager(originalSecurityManager);
            }
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
            
            if (customSecurityManagerSet) {
              System.setSecurityManager(originalSecurityManager);
            }
            System.exit( exitCode );
        }
    }

    private static int[] getChannelIds() {
      if (reporterFactory == null) {
        return null;
      }
      return reporterFactory.getAllChannelIds();
    }

    private static void sayBye(final PrintStream ps, final int channel) {
      // Say bye:
      ps.println( "Z,"+channel+",BYE!" );
      ps.flush();
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

        if (factory instanceof ReporterFactory) {
          reporterFactory = (ReporterFactory)factory;
        } else {
          reporterFactory = null;
        }
        
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
