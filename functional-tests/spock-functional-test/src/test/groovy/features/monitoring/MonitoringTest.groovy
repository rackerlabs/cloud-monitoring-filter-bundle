package features.monitoring

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy

/**
 * Created by dimi5963 on 8/11/15.
 */
class MonitoringTest  extends ReposeValveTest {

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

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

    def "When using forwarded-proto filter, Repose addes the x-forwarded-proto header to the request"() {
        given:
        def Map headers = ["x-rax-user": "test-user-a", "x-rax-groups": "reposegroup11"]

        when: "Request contains value(s) of the target header"
        def mc = deproxy.makeRequest([url: reposeEndpoint, headers: headers])

        then: "The x-forwarded-proto header is additionally added to the request going to the origin service"
        mc.getSentRequest().getHeaders().contains("x-rax-user")
        mc.getSentRequest().getHeaders().getFirstValue("x-rax-user") == "test-user-a"
        mc.getSentRequest().getHeaders().contains("x-forwarded-proto") == false
        mc.handlings[0].request.headers.contains("x-rax-user")
        mc.handlings[0].request.headers.getFirstValue("x-rax-user") == "test-user-a"
        mc.handlings[0].request.headers.contains("x-forwarded-proto")
        String forwardedProto = mc.handlings[0].request.headers.getFirstValue("x-forwarded-proto")
        forwardedProto.toLowerCase().contains("http")
    }
}