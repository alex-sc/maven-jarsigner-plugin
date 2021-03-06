package org.apache.maven.plugins.jarsigner;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.jarsigner.JarSigner;
import org.apache.maven.shared.jarsigner.JarSignerRequest;
import org.apache.maven.shared.jarsigner.JarSignerUtil;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.cli.Commandline;
import org.apache.maven.shared.utils.cli.javatool.JavaToolException;
import org.apache.maven.shared.utils.cli.javatool.JavaToolResult;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maven Jarsigner Plugin base class.
 *
 * @author <a href="cs@schulte.it">Christian Schulte</a>
 * @version $Id: AbstractJarsignerMojo.java 1640243 2014-11-17 22:12:18Z khmarbaise $
 */
public abstract class AbstractJarsignerMojo
    extends AbstractMojo
{

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.keystore" )
    private String keystore;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.storetype" )
    private String storetype;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.storepass" )
    private String storepass;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerName" )
    private String providerName;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerClass" )
    private String providerClass;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerArg" )
    private String providerArg;

    /**
     * See <a href="http://java.sun.com/javase/6/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.alias" )
    private String alias;

    /**
     * The maximum memory available to the JAR signer, e.g. <code>256M</code>. See <a
     * href="http://java.sun.com/javase/6/docs/technotes/tools/windows/java.html#Xms">-Xmx</a> for more details.
     */
    @Parameter( property = "jarsigner.maxMemory" )
    private String maxMemory;

    /**
     * Archive to process. If set, neither the project artifact nor any attachments or archive sets are processed.
     */
    @Parameter( property = "jarsigner.archive" )
    private File archive;

    /**
     * The base directory to scan for JAR files using Ant-like inclusion/exclusion patterns.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.archiveDirectory" )
    private File archiveDirectory;

    /**
     * The Ant-like inclusion patterns used to select JAR files to process. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}. By default, the pattern
     * <code>&#42;&#42;/&#42;.?ar</code> is used.
     *
     * @since 1.1
     */
    @Parameter
    private String[] includes = { "**/*.?ar" };

    /**
     * The Ant-like exclusion patterns used to exclude JAR files from processing. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}.
     *
     * @since 1.1
     */
    @Parameter
    private String[] excludes = {};

    /**
     * List of additional arguments to append to the jarsigner command line.
     */
    @Parameter( property = "jarsigner.arguments" )
    private String[] arguments;

    /**
     * Set to {@code true} to disable the plugin.
     */
    @Parameter( property = "jarsigner.skip", defaultValue = "false" )
    private boolean skip;

    /**
     * Controls processing of the main artifact produced by the project.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.processMainArtifact", defaultValue = "true" )
    private boolean processMainArtifact;

    /**
     * Controls processing of project attachments. If enabled, attached artifacts that are no JAR/ZIP files will be
     * automatically excluded from processing.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.processAttachedArtifacts", defaultValue = "true" )
    private boolean processAttachedArtifacts;

    /**
     * Must be set to true if the password must be given via a protected
     * authentication path such as a dedicated PIN reader.
     *
     * @since 1.3
     */
    @Parameter( property = "jarsigner.protectedAuthenticationPath", defaultValue = "false" )
    private boolean protectedAuthenticationPath;

    /**
     * Controls processing of project attachments.
     *
     * @deprecated As of version 1.1 in favor of the new parameter <code>processAttachedArtifacts</code>.
     */
    @Parameter( property = "jarsigner.attachments" )
    private Boolean attachments;

    /**
     * A set of artifact classifiers describing the project attachments that should be processed. This parameter is only
     * relevant if {@link #processAttachedArtifacts} is <code>true</code>. If empty, all attachments are included.
     *
     * @since 1.2
     */
    @Parameter
    private String[] includeClassifiers;

    /**
     * A set of artifact classifiers describing the project attachments that should not be processed. This parameter is
     * only relevant if {@link #processAttachedArtifacts} is <code>true</code>. If empty, no attachments are excluded.
     *
     * @since 1.2
     */
    @Parameter
    private String[] excludeClassifiers;

    /**
     * The Maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * Location of the working directory.
     *
     * @since 1.3
     */
    @Parameter( defaultValue = "${project.basedir}" )
    private File workingDirectory;

    /**
     */
    @Component
    private JarSigner jarSigner;

    /**
     * The current build session instance. This is used for
     * toolchain manager API calls.
     *
     * @since 1.3
     */
    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    /**
     * To obtain a toolchain if possible.
     *
     * @since 1.3
     */
    @Component
    private ToolchainManager toolchainManager;

    /**
     * @since 1.3.2
     */
    @Component( hint = "mng-4384" )
    private SecDispatcher securityDispatcher;

    public final void execute()
        throws MojoExecutionException
    {
        if ( !this.skip )
        {
            Toolchain toolchain = getToolchain();

            if ( toolchain != null )
            {
                getLog().info( "Toolchain in maven-jarsigner-plugin: " + toolchain );
                jarSigner.setToolchain( toolchain );
            }

            final AtomicInteger processed = new AtomicInteger( 0 );

            if ( this.archive != null )
            {
                processArchive( this.archive );
                processed.incrementAndGet();
            }
            else
            {
                if ( processMainArtifact )
                {
                    if ( processArtifact( this.project.getArtifact() ) )
                    {
                        processed.incrementAndGet();
                    }
                }

                if ( processAttachedArtifacts && !Boolean.FALSE.equals( attachments ) )
                {
                    Collection<String> includes = new HashSet<String>();
                    if ( includeClassifiers != null )
                    {
                        includes.addAll( Arrays.asList( includeClassifiers ) );
                    }

                    Collection<String> excludes = new HashSet<String>();
                    if ( excludeClassifiers != null )
                    {
                        excludes.addAll( Arrays.asList( excludeClassifiers ) );
                    }

                    for ( Object o : this.project.getAttachedArtifacts() )
                    {
                        final Artifact artifact = (Artifact) o;

                        if ( !includes.isEmpty() && !includes.contains( artifact.getClassifier() ) )
                        {
                            continue;
                        }

                        if ( excludes.contains( artifact.getClassifier() ) )
                        {
                            continue;
                        }

                        if ( processArtifact( artifact ) )
                        {
                            processed.incrementAndGet();
                        }
                    }
                }
                else
                {
                    if ( verbose )
                    {
                        getLog().info( getMessage( "ignoringAttachments" ) );
                    }
                    else
                    {
                        getLog().debug( getMessage( "ignoringAttachments" ) );
                    }
                }

                if ( archiveDirectory != null )
                {
                    String includeList = ( includes != null ) ? StringUtils.join( includes, "," ) : null;
                    String excludeList = ( excludes != null ) ? StringUtils.join( excludes, "," ) : null;

                    final List<File> jarFiles;
                    try
                    {
                        jarFiles = FileUtils.getFiles( archiveDirectory, includeList, excludeList );
                    }
                    catch ( IOException e )
                    {
                        throw new MojoExecutionException( "Failed to scan archive directory for JARs: "
                            + e.getMessage(), e );
                    }

                    final int numberOfForks = getNumberOfForks();
                    if ( numberOfForks <= 1 )
                    {
                        for ( File jarFile : jarFiles )
                        {
                            processArchive( jarFile );
                            processed.incrementAndGet();
                        }
                    }
                    else
                    {
                        final List<MojoExecutionException> errors =
                                Collections.synchronizedList( new ArrayList<MojoExecutionException>() );
                        ExecutorService executor = Executors.newFixedThreadPool( numberOfForks );
                        for ( int forkIndex = 0; forkIndex < numberOfForks; forkIndex++ )
                        {
                            final int forkId = forkIndex;
                            executor.execute( new Runnable()
                            {
                                public void run()
                                {
                                    for ( int i = forkId; i < jarFiles.size(); i += numberOfForks )
                                    {
                                        File jarFile = jarFiles.get( i );
                                        try
                                        {
                                            processArchive( jarFile );
                                        }
                                        catch ( MojoExecutionException e )
                                        {
                                            getLog().warn( "Error processing " + jarFile, e );
                                            errors.add( e );
                                        }
                                        processed.incrementAndGet();
                                    }
                                }
                            } );
                        }
                        executor.shutdown();
                        if ( !errors.isEmpty() )
                        {
                            throw errors.get( 0 );
                        }
                    }
                }
            }

            getLog().info( getMessage( "processed", processed.get() ) );
        }
        else
        {
            getLog().info( getMessage( "disabled", null ) );
        }
    }

    protected abstract int getNumberOfForks();

    /**
     * Creates the jar signer request to be executed.
     *
     * @param archive the archive file to treat by jarsigner
     * @return the request
     * @since 1.3
     */
    protected abstract JarSignerRequest createRequest( File archive )
        throws MojoExecutionException;

    /**
     * Gets a string representation of a {@code Commandline}.
     * <p>
     * This method creates the string representation by calling {@code commandLine.toString()} by default.
     * </p>
     *
     * @param commandLine The {@code Commandline} to get a string representation of.
     * @return The string representation of {@code commandLine}.
     * @throws NullPointerException if {@code commandLine} is {@code null}.
     */
    protected String getCommandlineInfo( final Commandline commandLine )
    {
        if ( commandLine == null )
        {
            throw new NullPointerException( "commandLine" );
        }

        String commandLineInfo = commandLine.toString();
        commandLineInfo = StringUtils.replace( commandLineInfo, this.storepass, "'*****'" );
        return commandLineInfo;
    }

    public String getStoretype()
    {
        return storetype;
    }

    public String getStorepass()
    {
        return storepass;
    }

    /**
     * Checks whether the specified artifact is a ZIP file.
     *
     * @param artifact The artifact to check, may be <code>null</code>.
     * @return <code>true</code> if the artifact looks like a ZIP file, <code>false</code> otherwise.
     */
    private boolean isZipFile( final Artifact artifact )
    {
        return artifact != null && artifact.getFile() != null && JarSignerUtil.isZipFile( artifact.getFile() );
    }

    /**
     * Processes a given artifact.
     *
     * @param artifact The artifact to process.
     * @return <code>true</code> if the artifact is a JAR and was processed, <code>false</code> otherwise.
     * @throws NullPointerException if {@code artifact} is {@code null}.
     * @throws MojoExecutionException if processing {@code artifact} fails.
     */
    private boolean processArtifact( final Artifact artifact )
        throws MojoExecutionException
    {
        if ( artifact == null )
        {
            throw new NullPointerException( "artifact" );
        }

        boolean processed = false;

        if ( isZipFile( artifact ) )
        {
            processArchive( artifact.getFile() );

            processed = true;
        }
        else
        {
            if ( this.verbose )
            {
                getLog().info( getMessage( "unsupported", artifact ) );
            }
            else if ( getLog().isDebugEnabled() )
            {
                getLog().debug( getMessage( "unsupported", artifact ) );
            }
        }

        return processed;
    }

    /**
     * Pre-processes a given archive.
     *
     * @param archive The archive to process, must not be <code>null</code>.
     * @throws MojoExecutionException If pre-processing failed.
     */
    protected void preProcessArchive( final File archive )
        throws MojoExecutionException
    {
        // default does nothing
    }

    /**
     * Processes a given archive.
     *
     * @param archive The archive to process.
     * @throws NullPointerException if {@code archive} is {@code null}.
     * @throws MojoExecutionException if processing {@code archive} fails.
     */
    private void processArchive( final File archive )
        throws MojoExecutionException
    {
        if ( archive == null )
        {
            throw new NullPointerException( "archive" );
        }

        preProcessArchive( archive );

        if ( this.verbose )
        {
            getLog().info( getMessage( "processing", archive ) );
        }
        else if ( getLog().isDebugEnabled() )
        {
            getLog().debug( getMessage( "processing", archive ) );
        }

        JarSignerRequest request = createRequest( archive );
        request.setVerbose( verbose );
        request.setAlias( alias );
        request.setArchive( archive );
        request.setKeystore( keystore );
        request.setStoretype( storetype );
        request.setProviderArg( providerArg );
        request.setProviderClass( providerClass );
        request.setProviderName( providerName );
        request.setWorkingDirectory( workingDirectory );
        request.setMaxMemory( maxMemory );
        request.setArguments( arguments );
        request.setProtectedAuthenticationPath( protectedAuthenticationPath );

        // Special handling for passwords through the Maven Security Dispatcher
        request.setStorepass( decrypt( storepass ) );

        try
        {
            JavaToolResult result = jarSigner.execute( request );

            Commandline commandLine = result.getCommandline();

            int resultCode = result.getExitCode();

            if ( resultCode != 0 )
            {
                // CHECKSTYLE_OFF: LineLength
                throw new MojoExecutionException( getMessage( "failure", getCommandlineInfo( commandLine ), resultCode ) );
                // CHECKSTYLE_ON: LineLength
            }

        }
        catch ( JavaToolException e )
        {
            throw new MojoExecutionException( getMessage( "commandLineException", e.getMessage() ), e );
        }
    }

    protected String decrypt( String encoded )
        throws MojoExecutionException
    {
        try
        {
            return securityDispatcher.decrypt( encoded );
        }
        catch ( SecDispatcherException e )
        {
            getLog().error( "error using security dispatcher: " + e.getMessage(), e );
            throw new MojoExecutionException( "error using security dispatcher: " + e.getMessage(), e );
        }
    }

    /**
     * Gets a message for a given key from the resource bundle backing the implementation.
     *
     * @param key The key of the message to return.
     * @param args Arguments to format the message with or {@code null}.
     * @return The message with key {@code key} from the resource bundle backing the implementation.
     * @throws NullPointerException if {@code key} is {@code null}.
     * @throws java.util.MissingResourceException
     *             if there is no message available matching {@code key} or accessing
     *             the resource bundle fails.
     */
    private String getMessage( final String key, final Object[] args )
    {
        if ( key == null )
        {
            throw new NullPointerException( "key" );
        }

        return new MessageFormat( ResourceBundle.getBundle( "jarsigner" ).getString( key ) ).format( args );
    }

    private String getMessage( final String key )
    {
        return getMessage( key, null );
    }

    String getMessage( final String key, final Object arg )
    {
        return getMessage( key, new Object[] { arg } );
    }

    private String getMessage( final String key, final Object arg1, final Object arg2 )
    {
        return getMessage( key, new Object[] { arg1, arg2 } );
    }

    /**
     * FIXME tchemit-20123-11-13, need to find out how to do this...
     * TODO remove the part with ToolchainManager lookup once we depend on
     * 2.0.9 (have it as prerequisite). Define as regular component field then.
     *
     * @return Toolchain instance
     */
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }

        return tc;
    }
}
