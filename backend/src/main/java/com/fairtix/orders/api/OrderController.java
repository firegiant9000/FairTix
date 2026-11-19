package com.fairtix.orders.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.dto.OrderResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only: orders are created by {@code POST /api/payments/checkout}, which
 * takes the payment and checks queue clearance first.
 */
@Tag(name = "Orders", description = "Order retrieval")
@RestController
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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
