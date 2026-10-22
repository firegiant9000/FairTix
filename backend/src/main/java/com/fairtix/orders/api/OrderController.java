package com.fairtix.orders.api;

import com.fairtix.auth.application.VerifiedEmailGuard;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.dto.CreateOrderRequest;
import com.fairtix.orders.dto.OrderResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Orders", description = "Order creation and retrieval")
@RestController
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;
    private final VerifiedEmailGuard verifiedEmailGuard;

    public OrderController(OrderService orderService, VerifiedEmailGuard verifiedEmailGuard) {
        this.orderService = orderService;
        this.verifiedEmailGuard = verifiedEmailGuard;
    }

    @Operation(summary = "Create an order from confirmed holds")
    @ApiResponse(responseCode = "201", description = "Order created with tickets issued")
    @ApiResponse(responseCode = "400", description = "Invalid holds")
    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreateOrderRequest request) {
        verifiedEmailGuard.requireVerified(principal.getUserId(), "purchasing tickets");
        return OrderResponse.from(orderService.createOrder(principal.getUserId(), request.holdIds()));
    }

    @Operation(summary = "List the current user's orders")
    @ApiResponse(responseCode = "200", description = "List of orders")
    @GetMapping("/api/orders")
    public List<OrderResponse> listOrders(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return orderService.listOrdersWithDetails(principal.getUserId());
    }

    @Operation(summary = "Get order details")
    @ApiResponse(responseCode = "200", description = "Order found")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @GetMapping("/api/orders/{orderId}")
    public OrderResponse getOrder(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID orderId) {
        return orderService.getOrderWithDetails(orderId, principal.getUserId());
    }
}
