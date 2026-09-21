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

}
