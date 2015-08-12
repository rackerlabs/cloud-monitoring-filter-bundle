package features.monitoring

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Response

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST


/**
 * Created by dimi5963 on 8/11/15.
 */
class MonitoringTest  extends ReposeValveTest {
    def static monitoringEndpoint

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        monitoringEndpoint = deproxy.addEndpoint(properties.monitoringPort,
                'monitoring service', null, { return new Response(200) })


        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/monitoring", params)
        repose.start()
        repose.waitForNon500FromUrl(reposeEndpoint)
    }

    def cleanupSpec() {
        deproxy.shutdown()
        repose.stop()
    }

    def "When doing the test"() {
        given:
        def Map headers = ["x-rax-user": "test-user-a", "x-rax-groups": "reposegroup11"]

        when: "Do awesome request"
        def mc = deproxy.makeRequest([url: reposeEndpoint, headers: headers])

        then: "Pass all the things"
        assert mc.receivedResponse.code == Integer.toString(SC_BAD_REQUEST)
    }
}
