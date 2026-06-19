# 08 — Validation & error handling

Two related concerns: **reject bad input at the edge** (validation), and **turn every failure into a consistent, correct HTTP response** (error handling).

---

## Part A — Validation (Bean Validation)

### The concept

**Bean Validation** (JSR-380, the `jakarta.validation` annotations) lets you declare constraints *on the data itself* and have the framework enforce them automatically, instead of writing `if (x == null) throw ...` by hand in every method.

### The rule: validate at the boundary

Bad input should be rejected as early as possible — at the controller, before any business logic runs. This keeps the service free of defensive null-checks and gives the client a clear `400` immediately.

### The request DTOs carry the constraints

```java
public record CreateOrderRequest(
        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotEmpty(message = "an order must contain at least one item")
        @Valid                                  // cascade validation INTO each item
        List<CreateOrderItemRequest> items
) {}

public record CreateOrderItemRequest(
        @NotNull(message = "productId is required")
        UUID productId,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than 0")
        Integer quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.00", message = "unitPrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 2 decimal places")
        BigDecimal unitPrice
) {}
```

Two details worth pointing out:
- **`@Valid` on the list** cascades validation into each `CreateOrderItemRequest`. Without it, the item-level constraints (`@Positive`, etc.) wouldn't run. This is a common bug — nested objects aren't validated unless you cascade.
- **`@NotEmpty` vs `@NotNull` vs `@NotBlank`:** `@NotNull` (not null, but `[]` is allowed), `@NotEmpty` (not null **and** size > 0 — used for `items`), `@NotBlank` (for strings: not null and not just whitespace).

### Triggering it

The controller annotates the body with `@Valid`:

```java
public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request, ...) { ... }
```

When `@Valid` fails, Spring throws `MethodArgumentNotValidException` **before** your method body runs — the service never sees invalid data.

### Validation vs business rules (an important distinction)

- **Validation** = "is this request *well-formed*?" (required fields present, quantity positive). Lives on the DTO, returns `400`.
- **Business rules** = "is this *allowed right now*?" (can this order transition to that status?). Lives in the service/state machine, returns `409`.

Don't conflate them. `quantity = -1` is a `400` (malformed). `cancel a SHIPPED order` is a `409` (well-formed but not allowed). Keeping them separate is why the state machine returns `409`, not `400`.

---

## Part B — Centralized error handling

### The concept

Without centralization, every controller method would need try/catch, and different endpoints would return differently-shaped errors (some plain text, some JSON, inconsistent fields). A **global exception handler** turns exceptions into HTTP responses in one place, so:
- controllers stay clean (they just throw / let exceptions propagate),
- every error has the **same JSON shape**,
- each exception maps to the **semantically correct status code**.

### The implementation

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);             // 404
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStatusTransitionException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);              // 409
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "The order was modified concurrently; please retry.", req);  // 409
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, "Bad Request", "Validation failed",
                req.getRequestURI(), fieldErrors));                            // 400 + per-field detail
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadInput(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or invalid request: " + ex.getMessage(), req);  // 400
    }
}
```

### How `@RestControllerAdvice` works

It's an AOP-style cross-cutting component. Spring registers its `@ExceptionHandler` methods globally; when an exception propagates out of *any* controller, Spring finds the handler matching that exception type and invokes it. `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`, so the returned object is serialized to JSON.

### The status code map

| Exception | HTTP | Meaning |
|---|---|---|
| `MethodArgumentNotValidException` | **400** | request is malformed (validation failed) |
| `HttpMessageNotReadableException` / type mismatch | **400** | unparseable JSON / wrong type in path/query |
| `OrderNotFoundException` | **404** | the resource doesn't exist |
| `InvalidStatusTransitionException` | **409** | well-formed but conflicts with current state |
| `OptimisticLockingFailureException` | **409** | concurrent modification detected |

### One consistent error shape

```java
public record ErrorResponse(
        OffsetDateTime timestamp, int status, String error,
        String message, String path, List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) {}
}
```

Every error — 400, 404, or 409 — comes back in this shape:

```json
{
  "timestamp": "2026-06-19T09:30:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Illegal status transition: SHIPPED -> CANCELLED",
  "path": "/api/v1/orders/.../status",
  "fieldErrors": null
}
```

`fieldErrors` is only populated for validation failures (so the client can highlight the exact bad field); it's `null` otherwise. A predictable shape means clients write one error-handling path, not five.

### Custom exceptions express intent

```java
public class OrderNotFoundException extends RuntimeException { ... }       // → 404
public class InvalidStatusTransitionException extends RuntimeException { } // → 409
```

The service throws *domain* exceptions ("order not found," "illegal transition"), and the handler maps them to *HTTP* concerns. The service doesn't know or care about status codes — separation of concerns again. They extend `RuntimeException` (unchecked) so they also trigger transaction rollback (see [05](./05-transactions-and-jpa.md)).

---

## Likely interview questions

**Q: How does validation work and where does it run?**
Bean Validation annotations on the request records; `@Valid` on the controller param triggers them before the method body. Failures become `MethodArgumentNotValidException` → mapped to `400` with per-field messages. The service never sees invalid input.

**Q: `@NotNull` vs `@NotEmpty` vs `@NotBlank`?**
`@NotNull`: not null (empty collection/string allowed). `@NotEmpty`: not null and size ≥ 1. `@NotBlank`: string not null and contains non-whitespace.

**Q: Why is an illegal transition a 409, not a 400?**
`400` is for malformed requests the client can fix by changing the payload. `409` is for a valid request that conflicts with the resource's current state — the same request could succeed at a different time. Picking the right code matters for API consumers.

**Q: How do you keep error responses consistent?**
One `@RestControllerAdvice` maps every exception to a single `ErrorResponse` shape. Controllers don't try/catch; they throw domain exceptions and the advice translates them.

**Q: Why custom exceptions instead of throwing `ResponseStatusException`?**
Domain exceptions keep the service free of HTTP concerns and read as business language. The HTTP mapping lives in one place (the advice), so the same exception could map differently for, say, a gRPC entry point.

**Q: Do these exceptions roll back the transaction?**
Yes — they're unchecked (`RuntimeException`), and Spring rolls back on runtime exceptions by default. So a failed transition or not-found doesn't leave a half-written transaction.
