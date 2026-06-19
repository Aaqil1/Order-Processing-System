package com.peerislands.orders.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerislands.orders.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String createOrderJson(int... quantities) throws Exception {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (int q : quantities) {
            items.add(Map.of(
                    "productId", UUID.randomUUID().toString(),
                    "quantity", q,
                    "unitPrice", "10.00"));
        }
        return objectMapper.writeValueAsString(Map.of(
                "customerId", UUID.randomUUID().toString(),
                "items", items));
    }

    private JsonNode createOrder(int... quantities) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson(quantities)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void moveTo(String id, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().isOk());
    }

    @Test
    void createPersistsOrderWithItemsAndComputedTotal() throws Exception {
        JsonNode body = createOrder(2, 3); // 2*10 + 3*10 = 50.00
        org.assertj.core.api.Assertions.assertThat(body.get("status").asText()).isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(body.get("items")).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(body.get("totalAmount").asDouble()).isEqualTo(50.0);
        org.assertj.core.api.Assertions.assertThat(body.get("history")).hasSize(1);
    }

    @Test
    void getByIdReturnsOrder_andUnknownReturns404() throws Exception {
        JsonNode created = createOrder(1);
        String id = created.get("id").asText();

        mockMvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void listSupportsStatusFilterAndPagination() throws Exception {
        createOrder(1);
        createOrder(1);

        mockMvc.perform(get("/api/v1/orders").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.size", is(1)));

        mockMvc.perform(get("/api/v1/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status", is("PENDING")));

        mockMvc.perform(get("/api/v1/orders").param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void cancelPendingSucceeds_andCancelShippedConflicts() throws Exception {
        JsonNode pending = createOrder(1);
        String pendingId = pending.get("id").asText();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", pendingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        // A second cancel is now a conflict.
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", pendingId))
                .andExpect(status().isConflict());

        JsonNode shipped = createOrder(1);
        String shippedId = shipped.get("id").asText();
        moveTo(shippedId, "PROCESSING");
        moveTo(shippedId, "SHIPPED");

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", shippedId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    void updateStatusEnforcesStateMachine() throws Exception {
        JsonNode created = createOrder(1);
        String id = created.get("id").asText();

        // legal forward path
        moveTo(id, "PROCESSING");
        moveTo(id, "SHIPPED");
        moveTo(id, "DELIVERED");

        // illegal: DELIVERED is terminal
        mockMvc.perform(patch("/api/v1/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "PROCESSING"))))
                .andExpect(status().isConflict());
    }

    @Test
    void illegalSkipTransitionConflicts() throws Exception {
        JsonNode created = createOrder(1);
        String id = created.get("id").asText();

        mockMvc.perform(patch("/api/v1/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "SHIPPED"))))
                .andExpect(status().isConflict());
    }

    @Test
    void validationRejectsEmptyItemsAndNonPositiveQuantity() throws Exception {
        String noItems = objectMapper.writeValueAsString(Map.of(
                "customerId", UUID.randomUUID().toString(),
                "items", java.util.List.of()));
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noItems))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        String zeroQty = objectMapper.writeValueAsString(Map.of(
                "customerId", UUID.randomUUID().toString(),
                "items", java.util.List.of(Map.of(
                        "productId", UUID.randomUUID().toString(),
                        "quantity", 0,
                        "unitPrice", "5.00"))));
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroQty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyKeyReturnsSameOrderOnRetry() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = createOrderJson(1);

        MvcResult first = mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);
    }
}
