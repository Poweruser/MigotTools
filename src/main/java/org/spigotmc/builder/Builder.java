package org.spigotmc.builder;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import difflib.DiffUtils;
import difflib.Patch;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

public class Builder
{

    public static final String LOG_FILE = "BuildTools.log.txt";
    public static final boolean IS_WINDOWS = System.getProperty( "os.name" ).startsWith( "Windows" );
    public static final File CWD = new File( "." );
    public static final String MC_VERSION = "1.8";
    private static boolean dontUpdate;
    private static boolean skipCompile;
    private static boolean generateSource;
    private static boolean generateDocs;
    private static boolean dev;

    public static void main(String[] args) throws Exception
    {
        OptionParser parser = new OptionParser();
        OptionSpec<Void> disableCertFlag = parser.accepts( "disable-certificate-check" );
        OptionSpec<Void> dontUpdateFlag = parser.accepts( "dont-update" );
        OptionSpec<Void> skipCompileFlag = parser.accepts( "skip-compile" );
        OptionSpec<Void> generateSourceFlag = parser.accepts( "generate-source" );
        OptionSpec<Void> generateDocsFlag = parser.accepts( "generate-docs" );
        OptionSpec<Void> devFlag = parser.accepts( "dev" );

        OptionSet options = parser.parse( args );

        if ( options.has( disableCertFlag ) )
        {
            disableHttpsCertificateCheck();
        }
        dontUpdate = options.has( dontUpdateFlag );
        skipCompile = options.has( skipCompileFlag );
        generateSource = options.has( generateSourceFlag );
        generateDocs = options.has( generateDocsFlag );
        dev = options.has( devFlag );

        logOutput();

        if ( Float.parseFloat( System.getProperty( "java.class.version" ) ) < 51.0 )
        {
            System.err.println( "*** WARNING *** You are not using Java 7 or above. Although this will work, it is highly discouraged due to the security issues present." );
            System.err.println( "*** WARNING *** Use java -version to check your version and update as soon as possible." );
        }

        try
        {
            runProcess( CWD, "bash", "-c", "exit" );
        } catch ( Exception ex )
        {
            System.out.println( "You must run this jar through bash (msysgit)" );
            System.exit( 1 );
        }

        try
        {
            runProcess( CWD, "git", "config", "--global", "user.name" );
        } catch ( Exception ex )
        {
            System.out.println( "Git name not set, setting it to default value." );
            runProcess( CWD, "git", "config", "--global", "user.name", "BuildTools" );
        }
        try
        {
            runProcess( CWD, "git", "config", "--global", "user.email" );
        } catch ( Exception ex )
        {
            System.out.println( "Git email not set, setting it to default value." );
            runProcess( CWD, "git", "config", "--global", "user.email", "unconfigured@null.spigotmc.org" );
        }

        File workDir = new File( "work" );
        workDir.mkdir();

        File bukkit = new File( "Bukkit" );
        if ( !bukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git", bukkit );
        }

        File craftBukkit = new File( "CraftBukkit" );
        if ( !craftBukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", craftBukkit );
        }

        File spigot = new File( "Spigot" );
        if ( !spigot.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/spigot.git", spigot );
        }

        File buildData = new File( "BuildData" );
        if ( !buildData.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildData );
        }

        File maven = new File( "apache-maven-3.2.3" );
        if ( !maven.exists() )
        {
            System.out.println( "Maven does not exist, downloading. Please wait." );

            File mvnTemp = new File( "mvn.zip" );
            mvnTemp.deleteOnExit();

            download( "http://static.spigotmc.org/maven/apache-maven-3.2.3-bin.zip", mvnTemp );
            unzip( mvnTemp, new File( "." ) );
        }

        String mvn = maven.getAbsolutePath() + "/bin/mvn";

        Git bukkitGit = Git.open( bukkit );
        Git craftBukkitGit = Git.open( craftBukkit );
        Git spigotGit = Git.open( spigot );
        Git buildGit = Git.open( buildData );

        BuildInfo buildInfo = new BuildInfo( "Dev Build", "Development", new BuildInfo.Refs( "master", "master", "master", "master" ) );

        if ( !dontUpdate )
        {
            if ( !dev )
            {
                System.out.println( "Checking for latest version:" );

                String verInfo;
                try
                {
                    verInfo = get( "https://hub.spigotmc.org/versions/latest.json" );
                } catch ( IOException ex )
                {
                    System.err.println( "Could not get latest version" );
                    ex.printStackTrace();
                    return;
                }
                System.out.println( "Found latest version" );
                System.out.println( verInfo );

                buildInfo = new Gson().fromJson( verInfo, BuildInfo.class );
            }

            pull( buildGit, buildInfo.getRefs().getBuildData() );
            pull( bukkitGit, buildInfo.getRefs().getBukkit() );
            pull( craftBukkitGit, buildInfo.getRefs().getCraftBukkit() );
            pull( spigotGit, buildInfo.getRefs().getSpigot() );
        }

        File vanillaJar = new File( workDir, "minecraft_server." + MC_VERSION + ".jar" );
        if ( !vanillaJar.exists() )
        {
            download( String.format( "https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar", MC_VERSION ), vanillaJar );
        }

        Iterable<RevCommit> mappings = buildGit.log()
                .addPath( "mappings/bukkit-1.8.at" )
                .addPath( "mappings/bukkit-1.8-cl.csrg" )
                .addPath( "mappings/bukkit-1.8-members.csrg" )
                .addPath( "mappings/package.srg" )
                .setMaxCount( 1 ).call();

        Hasher mappingsHash = Hashing.md5().newHasher();
        for ( RevCommit rev : mappings )
        {
            mappingsHash.putString( rev.getName(), Charsets.UTF_8 );
        }
        String mappingsVersion = mappingsHash.hash().toString().substring( 24 ); // Last 8 chars

        File finalMappedJar = new File( workDir, "mapped." + mappingsVersion + ".jar" );
        if ( !finalMappedJar.exists() )
        {
            System.out.println( "Final mapped jar: " + finalMappedJar + " does not exist, creating!" );

            File clMappedJar = new File( finalMappedJar + "-cl" );
            File mMappedJar = new File( finalMappedJar + "-m" );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource-2.jar", "map", "-i", vanillaJar.getPath(), "-m", "BuildData/mappings/bukkit-1.8-cl.csrg", "-o", clMappedJar.getPath() );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource-2.jar", "map", "-i", clMappedJar.getPath(),
                    "-m", "BuildData/mappings/bukkit-1.8-members.csrg", "-o", mMappedJar.getPath() );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource.jar", "-i", mMappedJar.getPath(), "--access-transformer", "BuildData/mappings/bukkit-1.8.at",
                    "-m", "BuildData/mappings/package.srg", "-o", finalMappedJar.getPath() );
        }

        runProcess( CWD, "sh", mvn, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
                "-DartifactId=minecraft-server", "-Dversion=1.8-SNAPSHOT" );

        File decompileDir = new File( workDir, "decompile-" + mappingsVersion );
        if ( !decompileDir.exists() )
        {
            decompileDir.mkdir();

            File clazzDir = new File( decompileDir, "classes" );
            unzip( finalMappedJar, clazzDir, new Predicate<String>()
            {

                @Override
                public boolean apply(String input)
                {
                    return input.startsWith( "net/minecraft/server" );
                }
            } );

            runProcess( CWD, "java", "-jar", "BuildData/bin/fernflower.jar", "-dgs=1", "-hdc=0", "-rbr=0", "-asc=1", "-udv=0", clazzDir.getPath(), decompileDir.getPath() );
        }

        System.out.println( "Applying CraftBukkit Patches" );
        File nmsDir = new File( craftBukkit, "src/main/java/net" );
        if ( nmsDir.exists() )
        {
            System.out.println( "Backing up NMS dir" );
            FileUtils.moveDirectory( nmsDir, new File( workDir, "nms.old." + System.currentTimeMillis() ) );
        }
        File patchDir = new File( craftBukkit, "nms-patches" );
        for ( File file : patchDir.listFiles() )
        {
            String targetFile = "net/minecraft/server/" + file.getName().replaceAll( ".patch", ".java" );

            File clean = new File( decompileDir, targetFile );
            File t = new File( nmsDir.getParentFile(), targetFile );
            t.getParentFile().mkdirs();

            System.out.println( "Patching with " + file.getName() );

            List<String> readFile = Files.readLines( file, Charsets.UTF_8 );

            // Manually append prelude if it is not found in the first few lines.
            boolean preludeFound = false;
            for ( int i = 0; i < Math.min( 3, readFile.size() ); i++ )
            {
                if ( readFile.get( i ).startsWith( "+++" ) )
                {
                    preludeFound = true;
                    break;
                }
            }
            if ( !preludeFound )
            {
                readFile.add( 0, "+++" );
            }

            Patch parsedPatch = DiffUtils.parseUnifiedDiff( readFile );
            List<?> modifiedLines = DiffUtils.patch( Files.readLines( clean, Charsets.UTF_8 ), parsedPatch );

            BufferedWriter bw = new BufferedWriter( new FileWriter( t ) );
            for ( String line : (List<String>) modifiedLines )
            {
                bw.write( line );
                bw.newLine();
            }
            bw.close();
        }
        File tmpNms = new File( craftBukkit, "tmp-nms" );
        FileUtils.copyDirectory( nmsDir, tmpNms );

        craftBukkitGit.branchDelete().setBranchNames( "patched" ).setForce( true ).call();
        craftBukkitGit.checkout().setCreateBranch( true ).setForce( true ).setName( "patched" ).call();
        craftBukkitGit.add().addFilepattern( "src/main/java/net/" ).call();
        craftBukkitGit.commit().setMessage( "CraftBukkit $ " + new Date() ).call();
        craftBukkitGit.checkout().setName( buildInfo.getRefs().getCraftBukkit() ).call();

        FileUtils.moveDirectory( tmpNms, nmsDir );

        File spigotApi = new File( spigot, "Bukkit" );
        if ( !spigotApi.exists() )
        {
            clone( "file://" + bukkit.getAbsolutePath(), spigotApi );
        }
        File spigotServer = new File( spigot, "CraftBukkit" );
        if ( !spigotServer.exists() )
        {
            clone( "file://" + craftBukkit.getAbsolutePath(), spigotServer );
        }

        // Git spigotApiGit = Git.open( spigotApi );
        // Git spigotServerGit = Git.open( spigotServer );
        if ( !skipCompile )
        {
            System.out.println( "Compiling Bukkit" );
            runProcess( bukkit, "sh", mvn, "clean", "install" );
            if ( generateDocs )
            {
                runProcess( bukkit, "sh", mvn, "javadoc:jar" );
            }
            if ( generateSource )
            {
                runProcess( bukkit, "sh", mvn, "source:jar" );
            }

            System.out.println( "Compiling CraftBukkit" );
            runProcess( craftBukkit, "sh", mvn, "clean", "install" );
        }

        try
        {
            runProcess( spigot, "bash", "applyPatches.sh" );
            System.out.println( "*** Spigot patches applied!" );
            System.out.println( "Compiling Spigot & Spigot-API" );

            if ( !skipCompile )
            {
                runProcess( spigot, "sh", mvn, "clean", "install" );
            }
        } catch ( Exception ex )
        {
            System.err.println( "Error compiling Spigot, are you running this jar via msysgit?" );
            ex.printStackTrace();
            System.exit( 1 );
        }

        for ( int i = 0; i < 35; i++ )
        {
            System.out.println( " " );
        }
        System.out.println( "Success! Everything compiled successfully. Copying final .jar files now." );
        copyJar( "CraftBukkit/target", "craftbukkit", "craftbukkit-" + MC_VERSION + ".jar" );
        copyJar( "Spigot/Spigot-Server/target", "spigot", "spigot-" + MC_VERSION + ".jar" );
    }

    public static final String get(String url) throws IOException
    {
        URLConnection con = new URL( url ).openConnection();
        con.setConnectTimeout( 5000 );
        con.setReadTimeout( 5000 );

        InputStreamReader r = null;
        try
        {
            r = new InputStreamReader( con.getInputStream() );

            return CharStreams.toString( r );
        } finally
        {
            if ( r != null )
            {
                r.close();
            }
        }
    }

    public static void copyJar(String path, final String jarPrefix, String outJarName) throws Exception
    {
        File[] files = new File( path ).listFiles( new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return name.startsWith( jarPrefix ) && name.endsWith( ".jar" );
            }
        } );
        for ( File file : files )
        {
            System.out.println( "Copying " + file.getName() + " to " + CWD.getAbsolutePath() );
            Files.copy( file, new File( CWD, outJarName ) );
            System.out.println( "  - Saved as " + outJarName );
        }
    }

    public static void pull(Git repo, String ref) throws Exception
    {
        System.out.println( "Pulling updates for " + repo.getRepository().getDirectory() );

        repo.reset().setRef( "origin/master" ).setMode( ResetCommand.ResetType.HARD ).call();
        repo.fetch().call();

        System.out.println( "Successfully fetched updates!" );

        repo.reset().setRef( ref ).setMode( ResetCommand.ResetType.HARD).call();
        if ( ref.equals( "master" ) )
        {
            repo.reset().setRef( "origin/master" ).setMode( ResetCommand.ResetType.HARD ).call();
        }
        System.out.println( "Checked out: " + ref );
    }

    public static int runProcess(File workDir, String... command) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder( command );
        pb.directory( workDir );
        pb.environment().put( "JAVA_HOME", System.getProperty( "java.home" ) );
        if ( !pb.environment().containsKey( "MAVEN_OPTS" ) )
        {
            pb.environment().put( "MAVEN_OPTS", "-Xmx1024M" );
        }

        final Process ps = pb.start();

        new Thread( new StreamRedirector( ps.getInputStream(), System.out ) ).start();
        new Thread( new StreamRedirector( ps.getErrorStream(), System.err ) ).start();

        int status = ps.waitFor();

        if ( status != 0 )
        {
            throw new RuntimeException( "Error running command, return status !=0: " + Arrays.toString( command ) );
        }

        return status;
    }

    @RequiredArgsConstructor
    private static class StreamRedirector implements Runnable
    {

        private final InputStream in;
        private final PrintStream out;

        @Override
        public void run()
        {
            BufferedReader br = new BufferedReader( new InputStreamReader( in ) );
            try
            {
                String line;
                while ( ( line = br.readLine() ) != null )
                {
                    out.println( line );
                }
            } catch ( IOException ex )
            {
                throw Throwables.propagate( ex );
            }
        }
    }

    public static void unzip(File zipFile, File targetFolder) throws IOException
    {
        unzip( zipFile, targetFolder, null );
    }

    public static void unzip(File zipFile, File targetFolder, Predicate<String> filter) throws IOException
    {
        targetFolder.mkdir();
        ZipFile zip = new ZipFile( zipFile );

        for ( Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); )
        {
            ZipEntry entry = entries.nextElement();

            if ( filter != null )
            {
                if ( !filter.apply( entry.getName() ) )
                {
                    continue;
                }
            }

            File outFile = new File( targetFolder, entry.getName() );

            if ( entry.isDirectory() )
            {
                outFile.mkdirs();
                continue;
            }
            if ( outFile.getParentFile() != null )
            {
                outFile.getParentFile().mkdirs();
            }

            InputStream is = zip.getInputStream( entry );
            OutputStream os = new FileOutputStream( outFile );
            try
            {
                ByteStreams.copy( is, os );
            } finally
            {
                is.close();
                os.close();
            }

            System.out.println( "Extracted: " + outFile );
        }
    }

    public static void clone(String url, File target) throws GitAPIException
    {
        System.out.println( "Starting clone of " + url + " to " + target );

        Git result = Git.cloneRepository().setURI( url ).setDirectory( target ).call();

        try
        {
            System.out.println( "Cloned git repository " + url + " to " + target.getAbsolutePath() + ". Current HEAD: " + commitHash( result ) );

        } finally
        {
            result.close();
        }
    }

    public static String commitHash(Git repo) throws GitAPIException
    {
        return Iterables.getOnlyElement( repo.log().setMaxCount( 1 ).call() ).getName();
    }

    public static File download(String url, File target) throws IOException
    {
        System.out.println( "Starting download of " + url );

        byte[] bytes = Resources.toByteArray( new URL( url ) );

        System.out.println( "Downloaded file: " + target + " with md5: " + Hashing.md5().hashBytes( bytes ).toString() );

        Files.write( bytes, target );

        return target;
    }

    public static void disableHttpsCertificateCheck()
    {
        // This globally disables certificate checking
        // http://stackoverflow.com/questions/19723415/java-overriding-function-to-disable-ssl-certificate-check
        try
        {
            TrustManager[] trustAllCerts = new TrustManager[]
            {
                new X509TrustManager()
                {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType)
                    {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType)
                    {
                    }
                }
            };

            // Trust SSL certs
            SSLContext sc = SSLContext.getInstance( "SSL" );
            sc.init( null, trustAllCerts, new SecureRandom() );
            HttpsURLConnection.setDefaultSSLSocketFactory( sc.getSocketFactory() );

            // Trust host names
            HostnameVerifier allHostsValid = new HostnameVerifier()
            {
                @Override
                public boolean verify(String hostname, SSLSession session)
                {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier( allHostsValid );
        } catch ( NoSuchAlgorithmException ex )
        {
            System.out.println( "Failed to disable https certificate check" );
            ex.printStackTrace( System.err );
        } catch ( KeyManagementException ex )
        {
            System.out.println( "Failed to disable https certificate check" );
            ex.printStackTrace( System.err );
        }
    }

    public static void logOutput()
    {
        try
        {
            final OutputStream logOut = new BufferedOutputStream( new FileOutputStream( LOG_FILE ) );

            Runtime.getRuntime().addShutdownHook( new Thread()
            {
                @Override
                public void run()
                {
                    System.setOut( new PrintStream( new FileOutputStream( FileDescriptor.out ) ) );
                    System.setErr( new PrintStream( new FileOutputStream( FileDescriptor.err ) ) );
                    try
                    {
                        logOut.close();
                    } catch ( IOException ex )
                    {
                        // We're shutting the jvm down anyway.
                    }
                }
            } );

            System.setOut( new PrintStream( new TeeOutputStream( System.out, logOut ) ) );
            System.setErr( new PrintStream( new TeeOutputStream( System.err, logOut ) ) );
        } catch ( FileNotFoundException ex )
        {
            System.err.println( "Failed to create log file: " + LOG_FILE );
        }
    }
}
