package com.orbitamarket.e2e;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E2E тесты для OrbitaMarket через API Gateway.
 * <p>
 * Требования: запущенный стенд OrbitaMarket (docker compose up).
 * Gateway по умолчанию: http://localhost:18080
 * <p>
 * Сценарии:
 * 1. Happy path: счет -> пополнение 1000 -> заказ на 120 -> PAID, баланс 880
 * 2. Недостаточно средств: баланс 50 -> заказ на 120 -> PAYMENT_FAILED, баланс 50
 * 3. Повтор OrderPaymentRequested с тем же order_id -> баланс не списан повторно
 * 4. Два заказа по 400 при балансе 1000 -> оба PAID, итог 200
 * 5. Повторный POST /accounts -> дубликат не создается
 * 6. GET /orders и GET /orders/{order_id} -> только заказы текущего пользователя
 * 7. Валидация ошибок -> единый формат error_code/message/timestamp
 * 8. Невалидный заказ -> HTTP 400 и сохраненный REJECTED с failure_reason
 * 9. TASKING и MONITORING -> успешная асинхронная оплата
 * 10. Документированные HTTP-ошибки -> требуемые статусы и error_code
 */
@Epic("OrbitaMarket")
@Feature("Payments and orders through API Gateway")
class OrbitaMarketE2ETest {
    private static final String USER_HEADER = "X-User-Id";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = System.getProperty("gateway.url", "http://localhost:18080");
    }

    @Test
    void happyPathAccountTopUpOrderPaidAndBalanceReduced() {
        String userId = user();

        createAccount(userId);
        topUp(userId, 1000);
        String orderId = createArchiveOrder(userId, 120);

        await().untilAsserted(() -> assertOrderStatus(userId, orderId, "PAID"));
        assertBalance(userId, 880);
    }

    @Test
    void insufficientBalanceKeepsBalanceAndMarksPaymentFailed() {
        String userId = user();

        createAccount(userId);
        topUp(userId, 50);
        String orderId = createArchiveOrder(userId, 120);

        await().untilAsserted(() -> assertOrderStatus(userId, orderId, "PAYMENT_FAILED"));
        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders/{orderId}", orderId)
                .then()
                .statusCode(200)
                .body("failure_reason", equalTo("INSUFFICIENT_BALANCE"));
        assertBalance(userId, 50);
    }

    @Test
    void repeatedPaymentRequestDoesNotChargeTwice() {
        String userId = user();

        createAccount(userId);
        topUp(userId, 1000);
        String orderId = createArchiveOrder(userId, 120);

        await().untilAsserted(() -> assertOrderStatus(userId, orderId, "PAID"));
        assertBalance(userId, 880);

        given()
                .header(USER_HEADER, userId)
                .post("/orders/orders/{orderId}/republish-payment-request", orderId)
                .then()
                .statusCode(202);

        await().untilAsserted(() -> assertBalance(userId, 880));
    }

    @Test
    void twoOrdersForFourHundredLeaveTwoHundredBalance() throws Exception {
        String userId = user();

        createAccount(userId);
        topUp(userId, 1000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tasks = List.of(
                    () -> createArchiveOrder(userId, 400),
                    () -> createArchiveOrder(userId, 400)
            );
            List<String> orderIds = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            await().untilAsserted(() -> {
                for (String orderId : orderIds) {
                    assertOrderStatus(userId, orderId, "PAID");
                }
                assertBalance(userId, 200);
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateAccountCreationIsIdempotent() {
        String userId = user();

        String firstAccountId = createAccount(userId);
        String secondAccountId = createAccount(userId);

        assertEquals(firstAccountId, secondAccountId);
    }

    @Test
    void listOrdersReturnsCreatedOrdersForCurrentUser() {
        String userId = user();

        createAccount(userId);
        topUp(userId, 500);
        String orderId = createArchiveOrder(userId, 100);

        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].order_id", notNullValue());

        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders/{orderId}", orderId)
                .then()
                .statusCode(200)
                .body("order_id", equalTo(orderId));
    }

    @Test
    void validationErrorsUseRequiredErrorFormat() {
        given()
                .post("/payments/accounts")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("MISSING_USER_ID"))
                .body("timestamp", notNullValue());

        String userId = user();
        createAccount(userId);
        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"amount\":0}")
                .post("/payments/accounts/top-up")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("INVALID_AMOUNT"));

        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"product_type\":\"UNKNOWN\",\"price\":100,\"payload\":{}}")
                .post("/orders/orders")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("UNKNOWN_PRODUCT_TYPE"));
    }

    @Test
    void invalidOrderIsPersistedAsRejected() {
        String userId = user();

        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"product_type\":\"UNKNOWN\",\"price\":100,\"payload\":{}}")
                .post("/orders/orders")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("UNKNOWN_PRODUCT_TYPE"));

        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders")
                .then()
                .statusCode(200)
                .body("find { it.status == 'REJECTED' }.failure_reason", equalTo("UNKNOWN_PRODUCT_TYPE"));
    }

    @Test
    void taskingAndMonitoringOrdersAreSupported() {
        String userId = user();

        createAccount(userId);
        topUp(userId, 1000);

        String taskingOrderId = createOrder(userId, """
                {
                  "product_type": "TASKING",
                  "price": 100,
                  "payload": {
                    "aoi": "POLYGON((37 55, 38 55, 38 56, 37 56, 37 55))",
                    "time_window": {
                      "from": "2026-07-15T00:00:00Z",
                      "to": "2026-07-16T00:00:00Z"
                    },
                    "sensor_type": "MSI"
                  }
                }
                """);
        String monitoringOrderId = createOrder(userId, """
                {
                  "product_type": "MONITORING",
                  "price": 150,
                  "payload": {
                    "aoi": "POLYGON((37 55, 38 55, 38 56, 37 56, 37 55))",
                    "cadence": "WEEKLY",
                    "duration_days": 30
                  }
                }
                """);

        await().untilAsserted(() -> {
            assertOrderStatus(userId, taskingOrderId, "PAID");
            assertOrderStatus(userId, monitoringOrderId, "PAID");
            assertBalance(userId, 750);
        });
    }

    @Test
    void documentedHttpErrorsAreReturned() {
        String userId = user();

        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"amount\":100}")
                .post("/payments/accounts/top-up")
                .then()
                .statusCode(404)
                .body("error_code", equalTo("ACCOUNT_NOT_FOUND"));

        given()
                .header(USER_HEADER, userId)
                .get("/payments/accounts/balance")
                .then()
                .statusCode(404)
                .body("error_code", equalTo("ACCOUNT_NOT_FOUND"));

        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders/{orderId}", UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("error_code", equalTo("ORDER_NOT_FOUND"));

        given()
                .get("/orders/orders")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("MISSING_USER_ID"));

        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"product_type\":\"ARCHIVE\",\"price\":0,\"payload\":{}}")
                .post("/orders/orders")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("INVALID_PRICE"));

        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"product_type\":\"ARCHIVE\",\"price\":100,\"payload\":{}}")
                .post("/orders/orders")
                .then()
                .statusCode(400)
                .body("error_code", equalTo("INVALID_PAYLOAD"));
    }

    private String createAccount(String userId) {
        return given()
                .header(USER_HEADER, userId)
                .post("/payments/accounts")
                .then()
                .statusCode(200)
                .body("account_id", notNullValue())
                .body("user_id", equalTo(userId))
                .extract()
                .path("account_id");
    }

    private void topUp(String userId, long amount) {
        given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body("{\"amount\":" + amount + "}")
                .post("/payments/accounts/top-up")
                .then()
                .statusCode(200)
                .body("balance", equalTo((int) amount));
    }

    private void assertBalance(String userId, int expectedBalance) {
        given()
                .header(USER_HEADER, userId)
                .get("/payments/accounts/balance")
                .then()
                .statusCode(200)
                .body("user_id", equalTo(userId))
                .body("balance", equalTo(expectedBalance))
                .body("currency", equalTo("geocredits"));
    }

    private String createArchiveOrder(String userId, long price) {
        return createOrder(userId, """
                {
                  "product_type": "ARCHIVE",
                  "price": %d,
                  "payload": {
                    "aoi": "POLYGON((37.0 55.0, 38.0 55.0, 38.0 56.0, 37.0 56.0, 37.0 55.0))",
                    "capture_date": "2024-06-15",
                    "sensor_type": "MSI"
                  }
                }
                """.formatted(price));
    }

    private String createOrder(String userId, String body) {
        return given()
                .header(USER_HEADER, userId)
                .contentType(ContentType.JSON)
                .body(body)
                .post("/orders/orders")
                .then()
                .statusCode(201)
                .body("order_id", notNullValue())
                .body("status", equalTo("PAYMENT_PENDING"))
                .extract()
                .path("order_id");
    }

    private void assertOrderStatus(String userId, String orderId, String expectedStatus) {
        given()
                .header(USER_HEADER, userId)
                .get("/orders/orders/{orderId}", orderId)
                .then()
                .statusCode(200)
                .body("status", equalTo(expectedStatus));
    }

    private String user() {
        return UUID.randomUUID().toString();
    }
}
