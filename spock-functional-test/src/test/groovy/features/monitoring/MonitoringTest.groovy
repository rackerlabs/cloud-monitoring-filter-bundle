package features.monitoring

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Endpoint
import org.rackspace.deproxy.Response
import spock.lang.Unroll

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

        requestBody | requestMethod | requestURI     | responseCode | responseMessage                            | headers                                       | monitoringResponse
        ""          | "GET"         | "/"            | "400"        | "No Entity ID in the URI."                 | []                                            | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        "BLAH"      | "POST"        | "/"            | "400"        | "No Entity ID in the URI."                 | []                                            | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity"      | "400"        | "No Entity ID in the URI."                 | []                                            | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/123"  | "401"        | "Not Authenticated."                       | []                                            | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/234"  | "200"        | "OK"                                       | ['x-auth-token': '123']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entity/345"  | "200"        | "OK"                                       | ['X-Auth-Token': '234']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entity/234"  | "200"        | "OK"                                       | ['x-auth-token': '123', "x-tenant-id": '123'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entity/345"  | "200"        | "OK"                                       | ['X-Auth-Token': '234', "x-tenant-id": '234'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entity/456"  | "500"        | "Invalid response from monitoring service" | ['X-Auth-Token': '345']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entity/567"  | "500"        | "Unknown Error"                            | ['x-auth-token': '456']                       | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entity/678"  | "500"        | "Unknown Error"                            | ['X-Auth-Token': '567']                       | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entity/789"  | "500"        | "Unknown Error"                            | ['x-auth-token': '678']                       | { return new Response(500, "Internal Server Error") }
        ""          | "GET"         | "/entity/890"  | "500"        | "Unknown Error"                            | ['X-Auth-Token': '789']                       | { return new Response(500, "Internal Server Error") }
    }
}
