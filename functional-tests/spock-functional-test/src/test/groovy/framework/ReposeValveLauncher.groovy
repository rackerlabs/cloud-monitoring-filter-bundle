/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package framework

import org.apache.http.client.ClientProtocolException
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.DefaultHttpClient
import org.linkedin.util.clock.SystemClock
import org.openrepose.commons.config.parser.jaxb.JaxbConfigurationParser
import org.openrepose.commons.config.resource.impl.BufferedURLConfigurationResource
import org.openrepose.core.filter.SystemModelInterrogator
import org.openrepose.core.systemmodel.SystemModel
import org.rackspace.deproxy.PortFinder

import java.util.concurrent.TimeoutException

import static org.linkedin.groovy.util.concurrent.GroovyConcurrentUtils.waitForCondition

class ReposeValveLauncher {

    def boolean debugEnabled
    def boolean doSuspend
    def String reposeJar
    def String configDir

    def clock = new SystemClock()

    def reposeEndpoint
    def int reposePort

    def debugPort = null
    def classPaths = []
    def additionalEnvironment = [:]

    Process process

    def ReposeConfigurationProvider configurationProvider

    ReposeValveLauncher(ReposeConfigurationProvider configurationProvider,
                        TestProperties properties) {
        this(configurationProvider,
                properties.reposeJar,
                properties.reposeEndpoint,
                properties.configDirectory,
                properties.reposePort
        )
    }

    ReposeValveLauncher(ReposeConfigurationProvider configurationProvider,
                        String reposeJar,
                        String reposeEndpoint,
                        String configDir,
                        int reposePort) {
        this.configurationProvider = configurationProvider
        this.reposeJar = reposeJar
        this.reposeEndpoint = reposeEndpoint
        this.reposePort = reposePort
        this.configDir = configDir
    }

    void start() {
        this.start([:])
    }

    void start(Map params) {

        boolean killOthersBeforeStarting = true
        if (params.containsKey("killOthersBeforeStarting")) {
            killOthersBeforeStarting = params.killOthersBeforeStarting
        }

        String clusterId = params.get('clusterId', "")
        String nodeId = params.get('nodeId', "")

        start(killOthersBeforeStarting, clusterId, nodeId)
    }

    /**
     * TODO: need to know what node in the system model we care about. There might be many, for multiple local node testing...
     * @param killOthersBeforeStarting
     */
    void start(boolean killOthersBeforeStarting, String clusterId, String nodeId) {

        File jarFile = new File(reposeJar)
        if (!jarFile.exists() || !jarFile.isFile()) {
            throw new FileNotFoundException("Missing or invalid Repose Valve Jar file.")
        }

        File configFolder = new File(configDir)
        if (!configFolder.exists() || !configFolder.isDirectory()) {
            throw new FileNotFoundException("Missing or invalid configuration folder.")
        }

        if (killOthersBeforeStarting) {
            waitForCondition(clock, '5s', '1s', {
                killIfUp()
                !isUp()
            })
        }

        def debugProps = ""
        def classPath = ""

        if (debugEnabled) {
            if (!debugPort) {
                debugPort = PortFinder.Singleton.getNextOpenPort()
            }
            debugProps = "-Xdebug -Xrunjdwp:transport=dt_socket,address=${debugPort},server=y,suspend="
            if (doSuspend) {
                debugProps += "y"
                println("\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\nConnect debugger to repose on port: ${debugPort}\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\n")
            } else {
                debugProps += "n"
            }
        }


        if (!classPaths.isEmpty()) {
            classPath = "-cp " + (classPaths as Set).join(";")
        }

        //TODO: possibly add a -Dlog4j.configurationFile to the guy so that we can load a different log4j config for early logging

        //Prepended the JUL logging manager from log4j2 so I can capture JUL logs, which are things in the JVM (like JMX)
        def cmd = "java -Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager -Xmx1536M -Xms1024M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump-${debugPort}.hprof -XX:MaxPermSize=128M $classPath $debugProps -jar $reposeJar -c $configDir"
        println("Starting repose: ${cmd}")

        def th = new Thread({
            //Construct a new environment, including all from the previous, and then overriding with our new one
            def newEnv = new HashMap<String, String>()
            newEnv.putAll(System.getenv())

            additionalEnvironment.each { k, v ->
                newEnv.put(k, v) //Should override anything, if there's anything to override
            }
            def envList = newEnv.collect { k, v -> "$k=$v" }
            this.process = cmd.execute(envList, null)
            this.process.consumeProcessOutput(System.out, System.err)
        });

        th.run()
        th.join()
    }


    void stop() {
        this.stop([:])
    }

    void stop(Map params) {
        def timeout = params?.timeout ?: 45000
        def throwExceptionOnKill = true

        if (params.containsKey("throwExceptionOnKill")) {
            throwExceptionOnKill = params.throwExceptionOnKill
        }

        stop(timeout, throwExceptionOnKill)
    }

    void stop(int timeout, boolean throwExceptionOnKill) {
        try {
            println("Stopping Repose");
            this.process?.destroy()

            print("Waiting for Repose to shutdown")
            waitForCondition(clock, "${timeout}", '1s', {
                print(".")
                !isUp()
            })

            println()
        } catch (IOException ioex) {
            this.process.waitForOrKill(5000)
            killIfUp()
            if (throwExceptionOnKill) {
                throw new TimeoutException("An error occurred while attempting to stop Repose Controller. Reason: " + ioex.getMessage());
            }
        } finally {
            configurationProvider.cleanConfigDirectory()
        }
    }

    void enableDebug() {
        this.debugEnabled = true
    }

    void enableSuspend() {
        this.debugEnabled = true
        this.doSuspend = true
    }

    void addToClassPath(String path) {
        classPaths.add(path)
    }

    /**
     * This takes a single string and will append it to the list of environment vars to be set for the .execute() method
     * Following docs from: http://groovy.codehaus.org/groovy-jdk/java/lang/String.html#execute%28java.util.List,%20java.io.File%29
     * @param environmentPair
     */
    void addToEnvironment(String key, String value) {
        additionalEnvironment.put(key, value)
    }

    /**
     * TODO: introspect the system model for expected filters in filter chain and validate that they
     * are all present and accounted for
     * @return
     */
    private boolean isReposeNodeUp(String clusterId, String nodeId) {
        print('.')

        //Marshal the SystemModel if possible, and try to get information from it about which node we care about....
        def systemModelFile = configurationProvider.getSystemModel()
        def systemModelXSDUrl = getClass().getResource("/META-INF/schema/system-model/system-model.xsd")
        def parser = JaxbConfigurationParser.getXmlConfigurationParser(SystemModel.class, systemModelXSDUrl, this.getClass().getClassLoader())
        def systemModel = parser.read(new BufferedURLConfigurationResource(systemModelFile.toURI().toURL()))

        //If the systemModel didn't validate, we're going to toss an exception here, which is fine

        //Get the systemModel cluster/node, if there's only one we can guess. If there's many, bad things happen.
        if (clusterId == "" || nodeId == "") {
            Map<String, List<String>> clusterNodes = SystemModelInterrogator.allClusterNodes(systemModel)

            if (clusterNodes.size() == 1) {
                clusterId = clusterNodes.keySet().toList().first()
                if (clusterNodes.get(clusterId).size() == 1) {
                    nodeId = clusterNodes.get(clusterId).first()
                } else {
                    throw new Exception("Unable to guess what nodeID you want in cluster: " + clusterId)
                }
            } else {
                throw new Exception("Unable to guess what clusterID you want!")
            }
        }

        return true

    }

    public boolean isUp() {
        println TestUtils.getJvmProcesses()
        return TestUtils.getJvmProcesses().contains("repose-valve.jar")
    }

    private static void killIfUp() {
        String processes = TestUtils.getJvmProcesses()
        def regex = /(\d*) repose-valve.jar .*spocktest .*/
        def matcher = (processes =~ regex)
        if (matcher.size() > 0) {

            for (int i = 1; i <= matcher.size(); i++) {
                String pid = matcher[0][i]

                if (pid != null && !pid.isEmpty()) {
                    println("Killing running repose-valve process: " + pid)
                    Runtime rt = Runtime.getRuntime();
                    if (System.getProperty("os.name").toLowerCase().indexOf("windows") > -1)
                        rt.exec("taskkill " + pid.toInteger());
                    else
                        rt.exec("kill -9 " + pid.toInteger());
                }
            }
        }
    }

    def waitForNon500FromUrl(url, int timeoutInSeconds = 60, int intervalInSeconds = 2) {

        waitForResponseCodeFromUrl(url, timeoutInSeconds, intervalInSeconds) { code -> code < 500 }
    }

    def waitForDesiredResponseCodeFromUrl(url, desiredCodes, timeoutInSeconds = 60, int intervalInSeconds = 2) {

        waitForResponseCodeFromUrl(url, timeoutInSeconds, intervalInSeconds) { code -> code in desiredCodes }
    }

    def waitForResponseCodeFromUrl(url, timeoutInSeconds, int intervalInSeconds, isResponseAcceptable) {

        print("\n\nWaiting for repose to start at ${url} \n\n")
        waitForCondition(clock, "${timeoutInSeconds}s", "${intervalInSeconds}s") {
            try {
                print(".")
                HttpClient client = new DefaultHttpClient()
                isResponseAcceptable(client.execute(new HttpGet(url)).statusLine.statusCode)
            } catch (IOException ignored) {
            } catch (ClientProtocolException ignored) {
            }
        }
        println()
    }
}
