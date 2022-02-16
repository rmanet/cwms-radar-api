package fixtures;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import javax.servlet.ServletContext;

import hthurow.tomcatjndi.TomcatJNDI;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.HostConfig;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;


/**
 * Tomcat server for ./gradle run and
 * Integration tests
 *
 * @Since 2021-11-05
 */
public class TomcatServer {
    private Tomcat tomcatInstance = null;
    private TomcatJNDI tomcatJndi = null;
    /**
     * Setups the baseline for tomcat to run.
     * @param baseDir set to the CATALINA_BASE directory the build has setup
     * @param radarWar points to the actual WAR file to load
     * @param port Network port to listen on
     * @param contextName url prefix to use, can be "/","/cwms-data","/spk-data"
     *                    etc
     * @throws Exception any error that gets thrown
     */
    public TomcatServer(final String baseDir,
                        final String radarWar,
                        final int port,
                        final String contextName,
                        final Realm realm
    ) throws Exception {

        tomcatInstance = new Tomcat();
        tomcatInstance.setBaseDir(baseDir);
        Host host = tomcatInstance.getHost();

        host.setAppBase("webapps");
        new File(tomcatInstance.getServer().getCatalinaBase(),"temp").mkdirs();
        new File(tomcatInstance.getServer().getCatalinaBase(),"webapps").mkdirs();
        tomcatInstance.setPort(port);
        Connector connector = tomcatInstance.getConnector();
        connector.setSecure(true);
        connector.setScheme("https");


        tomcatInstance.setSilent(false);
        tomcatInstance.enableNaming();
        Engine engine = tomcatInstance.getEngine();

        host.addLifecycleListener(new HostConfig());


        Context blankToNull = tomcatInstance.addContext("", null);


        File radar = new File(radarWar);
        try{
            File existingRadar = new File(tomcatInstance.getHost().getAppBaseFile().getAbsolutePath(),contextName);
            Files.walk(existingRadar.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            existingRadar.delete();
            new File(existingRadar.getAbsolutePath()+".war").delete();
        } catch( Exception err) {
            System.out.println(err.getLocalizedMessage());
        }

        Context context = tomcatInstance.addWebapp(contextName, radar.toURI().toURL());


        Class filterClass = UserInjectFilter.class;
        FilterDef myFilterDef = new FilterDef();
        myFilterDef.setFilterClass(filterClass.getName());
        myFilterDef.setFilterName(filterClass.getSimpleName());
        context.addFilterDef(myFilterDef);

        FilterMap myFilterMap = new FilterMap();
        myFilterMap.setFilterName(filterClass.getSimpleName());
        myFilterMap.addURLPattern("/*");
        context.addFilterMap(myFilterMap);


        if(realm != null)
        {
            engine.setRealm(realm);
        }

    }

    public TomcatServer(final String baseDir,
                        final String radarWar,
                        final int port,
                        final String contextName
    ) throws Exception{
        this(baseDir,radarWar,port,contextName,new TestRealm());
    }

    public int getPort() {
        return tomcatInstance.getConnector().getLocalPort();
    }

    /**
     * Starts the instance of tomcat and returns when it's ready.
     * @throws LifecycleException any error in the startup sequence
     */
    public void start() throws LifecycleException {
        tomcatInstance.start();
        System.out.println("Tomcat listening at http://localhost:" + tomcatInstance.getConnector().getPort());
    }

    /**
     * Used for the ./gradlew run command.
     * Unit tests only need to call start and move on.
     */
    public void await() {
        tomcatInstance.getServer().await();
    }

    /**
     * Stops the instance of tomcat, including destroying the JNDI context.
     * @throws LifecycleException any error in the stop sequence
     */
    public void stop() throws LifecycleException {
        tomcatInstance.stop();
        //tomcatJndi.tearDown();
    }

    /**
     * arg[0] - the CATALINA_BASE directory you've setup
     * arg[1] - full path to the war file generated by this build script
     * arg[2] - name to use for this instance. See constructor for guidance
     * @param args standard argument list
     */
    public static void main(String []args) {
        String baseDir = args[0];
        String radarWar = args[1];
        String contextName = args[2];
        int port = Integer.parseInt(System.getProperty("RADAR_LISTEN_PORT","0").trim());

        try {
            TestRealm realm = new TestRealm();
            TomcatServer tomcat = new TomcatServer(baseDir, radarWar, port, contextName, realm);
            tomcat.start();
            tomcat.await();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }

    }
}
