package com.fairtix.payments.dto;

import com.stripe.model.Balance;

import java.util.List;

public record ConnectDashboardResponse(
    boolean connected,
    ConnectAccountStatusResponse account,
    List<BalanceEntry> available,
    List<BalanceEntry> pending,
    List<ConnectPayoutResponse> recentPayouts
) {

  public record BalanceEntry(long amount, String currency) {}

  public static ConnectDashboardResponse notConnected(ConnectAccountStatusResponse account) {
    return new ConnectDashboardResponse(false, account, List.of(), List.of(), List.of());
  }

  public static ConnectDashboardResponse connected(ConnectAccountStatusResponse account,
                                                   Balance balance,
                                                   List<ConnectPayoutResponse> payouts) {
    List<BalanceEntry> available = balance == null || balance.getAvailable() == null
        ? List.of()
        : balance.getAvailable().stream()
            .map(b -> new BalanceEntry(b.getAmount() == null ? 0L : b.getAmount(), b.getCurrency()))
            .toList();
    List<BalanceEntry> pending = balance == null || balance.getPending() == null
        ? List.of()
        : balance.getPending().stream()
            .map(b -> new BalanceEntry(b.getAmount() == null ? 0L : b.getAmount(), b.getCurrency()))
            .toList();
    return new ConnectDashboardResponse(true, account, available, pending,
        payouts == null ? List.of() : payouts);
  }
}
