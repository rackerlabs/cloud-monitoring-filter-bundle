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

        requestBody | requestMethod | requestURI                          | responseCode | responseMessage                            | headers                                                                             | monitoringResponse
        ""          | "GET"         | "/"                                 | "400"        | "Bad Request"                              | []                                                                                  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        "BLAH"      | "POST"        | "/"                                 | "400"        | "Bad Request"                              | []                                                                                  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities"                         | "400"        | "Bad Request"                              | []                                                                                  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/123"                     | "401"        | "Unauthorized"                             | []                                                                                  | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/234"                     | "200"        | "OK"                                       | ['x-auth-token': '123', "x-roles": 'monitoring:intermediary']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/345"                     | "200"        | "OK"                                       | ['X-Auth-Token': '234', "x-roles": 'monitoring:intermediary']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/234"                     | "200"        | "OK"                                       | ['x-auth-token': '123', "x-tenant-id": '123', "x-roles": 'monitoring:intermediary'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/345"                     | "200"        | "OK"                                       | ['X-Auth-Token': '234', "x-tenant-id": '234', "x-roles": 'monitoring:intermediary'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/234/checks/someotherid"  | "200"        | "OK"                                       | ['x-auth-token': '123', "x-tenant-id": '123', "x-roles": 'monitoring:intermediary'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/345/alarms/someotherid"  | "200"        | "OK"                                       | ['X-Auth-Token': '234', "x-tenant-id": '234', "x-roles": 'monitoring:intermediary'] | { return new Response(200, "ok", ["content-type": "application/json"], "{\"uri\":\"devices/123\"}") }
        ""          | "GET"         | "/entities/456"                     | "200"        | "OK"                                       | ['X-Auth-Token': '345', "x-roles": 'monitoring:intermediary']                       | { return new Response(200, "ok", ["content-type": "application/json"], "{\"test\":\"data\"}") }
        ""          | "GET"         | "/entities/567"                     | "200"        | "OK"                                       | ['x-auth-token': '456', "x-roles": 'monitoring:intermediary']                       | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entities/678"                     | "200"        | "OK"                                       | ['X-Auth-Token': '567', "x-roles": 'monitoring:intermediary']                       | { return new Response(404, "Not Found") }
        ""          | "GET"         | "/entities/789"                     | "500"        | "Server Error"                             | ['x-auth-token': '678', "x-roles": 'monitoring:intermediary']                       | { return new Response(500, "Internal Server Error") }
        ""          | "GET"         | "/entities/890"                     | "500"        | "Server Error"                             | ['X-Auth-Token': '789', "x-roles": 'monitoring:intermediary']                       | { return new Response(500, "Internal Server Error") }
    }
}
