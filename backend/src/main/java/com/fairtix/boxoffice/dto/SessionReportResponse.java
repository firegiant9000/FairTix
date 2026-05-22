package com.fairtix.boxoffice.dto;

import java.math.BigDecimal;
import java.util.List;

public record SessionReportResponse(
    SessionResponse session,
    BigDecimal cashSalesTotal,
    BigDecimal cardSalesTotal,
    BigDecimal compsTotal,
    int cashSaleCount,
    int cardSaleCount,
    int compSaleCount,
    int ticketsSold,
    BigDecimal expectedCash,
    List<SaleResponse> sales) {}
