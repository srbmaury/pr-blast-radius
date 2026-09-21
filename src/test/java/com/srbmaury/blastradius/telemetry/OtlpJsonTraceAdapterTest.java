package com.srbmaury.blastradius.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpJsonTraceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OtlpJsonTraceAdapter adapter = new OtlpJsonTraceAdapter();

    @Test
    void extractsClientEdgeFromOtlpJson() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "resourceSpans": [
                    {
                      "resource": {
                        "attributes": [
                          {
                            "key": "service.name",
                            "value": {"stringValue": "orders-service"}
                          }
                        ]
                      },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "kind": "SPAN_KIND_CLIENT",
                              "startTimeUnixNano": "1789985700000000000",
                              "attributes": [
                                {
                                  "key": "peer.service",
                                  "value": {"stringValue": "payment-service"}
                                },
                                {
                                  "key": "http.request.method",
                                  "value": {"stringValue": "POST"}
                                },
                                {
                                  "key": "url.template",
                                  "value": {"stringValue": "/payments/{id}"}

                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        var observations = adapter.extract(payload);

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.sourceService()).isEqualTo("orders-service");
            assertThat(observation.targetService()).isEqualTo("payment-service");
            assertThat(observation.endpoint()).isEqualTo("HTTP POST /payments/{id}");
            assertThat(observation.spanKind()).isEqualTo("CLIENT");
        });
    }

    @Test
    void supportsNumericProducerKindAndIgnoresServerSpans() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "resourceSpans": [
                    {
                      "resource": {
                        "attributes": [
                          {
                            "key": "service.name",
                            "value": {"stringValue": "orders-service"}
                          }
                        ]
                      },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "kind": 4,
                              "attributes": [
                                {
                                  "key": "peer.service",
                                  "value": {"stringValue": "events-service"}
                                }
                              ]
                            },
                            {
                              "kind": 2,
                              "attributes": [
                                {
                                  "key": "peer.service",
                                  "value": {"stringValue": "ignored-service"}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        var observations = adapter.extract(payload);

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.targetService()).isEqualTo("events-service");
            assertThat(observation.spanKind()).isEqualTo("PRODUCER");
        });
    }

    @Test
    void extractsRpcEndpointIdentity() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "resourceSpans": [
                    {
                      "resource": {
                        "attributes": [
                          {
                            "key": "service.name",
                            "value": {"stringValue": "orders-service"}
                          }
                        ]
                      },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "kind": "SPAN_KIND_CLIENT",
                              "attributes": [
                                {
                                  "key": "peer.service",
                                  "value": {"stringValue": "payments-rpc"}
                                },
                                {
                                  "key": "rpc.system",
                                  "value": {"stringValue": "grpc"}
                                },
                                {
                                  "key": "rpc.service",
                                  "value": {"stringValue": "PaymentService"}
                                },
                                {
                                  "key": "rpc.method",
                                  "value": {"stringValue": "CreatePayment"}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        assertThat(adapter.extract(payload))
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.endpoint())
                                .isEqualTo("RPC grpc PaymentService/CreatePayment")
                );
    }


    @Test
    void retainsServerInternalAndClientLineageForTraceCausality() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "resourceSpans": [
                    {
                      "resource": {
                        "attributes": [
                          {
                            "key": "service.name",
                            "value": {"stringValue": "orders-service"}
                          }
                        ]
                      },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "traceId": "trace-a",
                              "spanId": "server-1",
                              "parentSpanId": "caller-client",
                              "kind": "SPAN_KIND_SERVER",
                              "startTimeUnixNano": "1789985700000000000",
                              "attributes": [
                                {
                                  "key": "http.request.method",
                                  "value": {"stringValue": "POST"}
                                },
                                {
                                  "key": "http.route",
                                  "value": {"stringValue": "/orders"}
                                }
                              ]
                            },
                            {
                              "traceId": "trace-a",
                              "spanId": "internal-1",
                              "parentSpanId": "server-1",
                              "kind": "SPAN_KIND_INTERNAL",
                              "startTimeUnixNano": "1789985700000001000",
                              "attributes": []
                            },
                            {
                              "traceId": "trace-a",
                              "spanId": "client-1",
                              "parentSpanId": "internal-1",
                              "kind": "SPAN_KIND_CLIENT",
                              "startTimeUnixNano": "1789985700000002000",
                              "attributes": [
                                {
                                  "key": "peer.service",
                                  "value": {"stringValue": "payment-service"}
                                },
                                {
                                  "key": "http.request.method",
                                  "value": {"stringValue": "POST"}
                                },
                                {
                                  "key": "url.template",
                                  "value": {"stringValue": "/payments"}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        var batch = adapter.extractBatch(payload);

        assertThat(batch.traceSpans())
                .hasSize(3)
                .anySatisfy(span -> {
                    assertThat(span.spanId()).isEqualTo("server-1");
                    assertThat(span.spanKind()).isEqualTo("SERVER");
                    assertThat(span.endpoint()).isEqualTo("HTTP POST /orders");
                })
                .anySatisfy(span -> {
                    assertThat(span.spanId()).isEqualTo("internal-1");
                    assertThat(span.spanKind()).isEqualTo("INTERNAL");
                    assertThat(span.parentSpanId()).isEqualTo("server-1");
                })
                .anySatisfy(span -> {
                    assertThat(span.spanId()).isEqualTo("client-1");
                    assertThat(span.spanKind()).isEqualTo("CLIENT");
                    assertThat(span.parentSpanId()).isEqualTo("internal-1");
                    assertThat(span.targetService()).isEqualTo("payment-service");
                });

        assertThat(batch.dependencyObservations())
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.endpoint())
                                .isEqualTo("HTTP POST /payments")
                );
    }

}
