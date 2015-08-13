package features.monitoring

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Endpoint
import org.rackspace.deproxy.Response
import spock.lang.Unroll

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST


/**
 * Created by dimi5963 on 8/11/15.
 */
class MonitoringTest  extends ReposeValveTest {
    static Endpoint monitoringEndpoint

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)
        monitoringEndpoint = deproxy.addEndpoint(properties.monitoringPort, 'monitoring service', null, { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") })

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

    def cleanup() {
        deproxy._removeEndpoint(monitoringEndpoint)
    }

    @Unroll("request: #requestMethod #requestURI -d #requestBody will return #responseCode with #responseMessage")
    def "When doing the test"() {
        given: "set up monitoring response"
        monitoringEndpoint.defaultHandler = monitoringResponse

        when: "Do awesome request"
        def mc = deproxy.makeRequest([
                url: reposeEndpoint + requestURI,
                method: requestMethod,
                requestBody: requestBody,
                headers: headers
        ])

        then: "Pass all the things"
        mc.receivedResponse.code == responseCode
        mc.receivedResponse.message == responseMessage

        where:

        requestBody | requestMethod | requestURI     | responseCode | responseMessage            | headers                  | monitoringResponse
        ""          | "GET"         | "/"            | "400"        | "No Entity ID in the URI." | []                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        "BLAH"      | "POST"        | "/"            | "400"        | "No Entity ID in the URI." | []                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity"      | "400"        | "No Entity ID in the URI." | []                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/123"  | "401"        | "Not Authenticated."       | []                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/123"  | "401"        | "Not Authenticated."       | ['x-auth-token': '123']  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/123"  | "200"        | "OK"                       | ['X-Auth-Token': '123']  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entity/123"  | "500"        | "Server Error"             | ['X-Auth-Token': '456']  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
    }
}
