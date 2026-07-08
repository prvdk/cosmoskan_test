# OrbitaMarket Tests

Публичный репозиторий автотестов для микросервисной платформы OrbitaMarket.

## О проекте

OrbitaMarket — платформа для заказа спутниковых продуктов (архивные снимки, tasking, monitoring) с оплатой внутренней валютой «геокредиты».  
Этот репозиторий содержит E2E-тесты, которые проверяют работу системы через API Gateway.

## Стек

- Java 21
- Maven
- JUnit 5
- RestAssured
- Awaitility
- Allure

## Предварительные требования

1. Запущенный стенд OrbitaMarket:
   ```bash
   docker compose up --build
   ```
   Gateway по умолчанию доступен на `http://localhost:18080`.

2. Java 21 и Maven установлены локально.

## Запуск тестов

```bash
mvn test
```

С указанием URL Gateway (если отличается от умолчания):

```bash
mvn test -Dgateway.url=http://localhost:18080
```

## Генерация Allure-отчёта

```bash
mvn allure:report
```

HTML-отчёт будет доступен в `target/site/allure-maven-plugin`.

## Чек-лист сценариев

| № | Сценарий | Ожидаемый результат | Метод теста |
|---|----------|---------------------|-------------|
| 1 | Счёт → пополнение 1000 → заказ на 120 | Статус `PAID`, баланс `880` | `happyPathAccountTopUpOrderPaidAndBalanceReduced` |
| 2 | Баланс 50 → заказ на 120 | `PAYMENT_FAILED`, баланс `50`, `failure_reason=INSUFFICIENT_BALANCE` | `insufficientBalanceKeepsBalanceAndMarksPaymentFailed` |
| 3 | Повтор `OrderPaymentRequested` с тем же `order_id` | Баланс не списан повторно | `repeatedPaymentRequestDoesNotChargeTwice` |
| 4 | Два заказа по 400 при балансе 1000 | Оба `PAID`, итоговый баланс `200` | `twoOrdersForFourHundredLeaveTwoHundredBalance` |
| 5 | Повторный `POST /accounts` для того же `user_id` | Возвращается существующий счёт, дубликат не создаётся | `duplicateAccountCreationIsIdempotent` |
| 6 | `GET /orders` и `GET /orders/{order_id}` | Возвращаются заказы текущего пользователя | `listOrdersReturnsCreatedOrdersForCurrentUser` |
| 7 | Ошибки валидации | Единый формат `error_code/message/timestamp` | `validationErrorsUseRequiredErrorFormat` |
| 8 | Невалидный заказ | HTTP 400, запись `REJECTED` с `failure_reason` | `invalidOrderIsPersistedAsRejected` |
| 9 | Заказы `TASKING` и `MONITORING` | Оба заказа переходят в `PAID` | `taskingAndMonitoringOrdersAreSupported` |
| 10 | Документированные HTTP-ошибки | Возвращаются требуемые статусы и `error_code` | `documentedHttpErrorsAreReturned` |

## Покрытые endpoint'ы

**Payments Service (через Gateway `/payments/**`):**
- `POST /payments/accounts` — создание счёта
- `POST /payments/accounts/top-up` — пополнение баланса
- `GET /payments/accounts/balance` — просмотр баланса

**Orders Service (через Gateway `/orders/**`):**
- `POST /orders/orders` — создание заказа
- `GET /orders/orders` — список заказов
- `GET /orders/orders/{order_id}` — детали заказа
- `POST /orders/orders/{order_id}/republish-payment-request` — повторная публикация запроса на оплату (служебный)

## Структура проекта

```
.
├── pom.xml
├── README.md
└── src/test/java/com/orbitamarket/e2e/
    └── OrbitaMarketE2ETest.java
```

## Связанные репозитории

- [OrbitaMarket](https://github.com/prvdk/orbita_market) — основной репозиторий с микросервисами
