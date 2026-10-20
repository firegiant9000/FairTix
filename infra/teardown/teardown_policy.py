"""The pure teardown decision (docs/infra/teardown-design.md §4).

decide() never reads the clock: `now` is a parameter. It fails closed: any expiry it
can't use while lab resources exist means "destroy", and an unknown resource kind
counts as billable.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Callable, Iterable

NOOP = "noop"
DESTROY = "destroy"

MAX_TTL = timedelta(hours=12)
REAPER_GRACE = timedelta(hours=2)

# TD §4: billable = ECS, ELB, RDS, ElastiCache, Elastic IPs, NAT. The reaper deletes these.
BILLABLE_KINDS = frozenset({
    "ecs_cluster",
    "ecs_service",
    "elb_load_balancer",
    "elb_target_group",
    "rds_instance",
    "rds_snapshot",
    "rds_automated_backup",
    "elasticache_cluster",
    "elasticache_replication_group",
    "elasticache_serverless_cache",
    "elasticache_snapshot",
    "eip",
    "nat_gateway",
})

# TD §4: free = VPC, security groups, ECR, secrets, log groups. ENIs and KMS keys are
# also here: layers 1-2 remove them, the reaper doesn't.
FREE_KINDS = frozenset({
    "vpc",
    "security_group",
    "eni",
    "ecr_repository",
    "secret",
    "log_group",
    "kms_key",
})


@dataclass(frozen=True)
class Item:
    kind: str
    id: str
    created: datetime | None = None


@dataclass(frozen=True)
class Census:
    billable: tuple[Item, ...] = ()
    free: tuple[Item, ...] = ()

    @classmethod
    def from_items(cls, items: Iterable[Item]) -> Census:
        billable, free = [], []
        for item in items:
            # Unknown kinds fail closed: billable, so the reaper sees them too.
            (free if item.kind in FREE_KINDS else billable).append(item)
        return cls(tuple(billable), tuple(free))

    def is_empty(self) -> bool:
        return not self.billable and not self.free

    def billable_only(self) -> Census:
        return Census(billable=self.billable)

    @property
    def oldest_created(self) -> datetime | None:
        """Oldest creation time among billable items; from free items only if billable is empty."""
        pool = self.billable if self.billable else self.free
        times = [i.created for i in pool if i.created is not None]
        return min(times) if times else None


class _Malformed(Exception):
    pass


def _parse_expiry(raw) -> datetime:
    if not isinstance(raw, str):
        raise _Malformed("expires_at missing or not a string")
    text = raw.strip()
    if not text or text.lower() == "none":
        raise _Malformed("expires_at missing")
    try:
        value = datetime.fromisoformat(text)
    except ValueError as exc:
        raise _Malformed(str(exc)) from exc
    if value.tzinfo is None or value.utcoffset() is None:
        raise _Malformed("expires_at has no timezone")
    return value


def decide(
    now: datetime,
    expires_at_raw,
    census: Census,
    max_ttl: timedelta = MAX_TTL,
    grace: timedelta = timedelta(0),
) -> str:
    """Return NOOP or DESTROY. Order: parse, then clamp, then compare (TD:122)."""
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("now must be timezone-aware")
    if census.is_empty():
        return NOOP

    oldest = census.oldest_created
    try:
        expires_at = _parse_expiry(expires_at_raw)
        if oldest is not None:
            ceiling = oldest + max_ttl
            if expires_at > ceiling:
                expires_at = ceiling
        elif expires_at > now + max_ttl:
            return DESTROY  # a far-future expiry that can't be clamped: tampering
        return DESTROY if now >= expires_at + grace else NOOP
    except (_Malformed, ArithmeticError, ValueError, TypeError):
        return _decide_unusable_expiry(now, oldest, grace)


def _decide_unusable_expiry(now: datetime, oldest: datetime | None, grace: timedelta) -> str:
    if grace <= timedelta(0) or oldest is None:
        return DESTROY
    try:
        return DESTROY if now >= oldest + grace else NOOP
    except (ArithmeticError, ValueError, TypeError):
        return DESTROY


def decide_for_layer2(now: datetime, expires_at_raw, census_fn: Callable[[], Census]) -> str:
    """Layer 2 (the scheduled workflow): full census, no grace. Any error means destroy."""
    try:
        return decide(now, expires_at_raw, census_fn())
    except Exception:  # noqa: BLE001 - fail closed: "resources may exist"
        return DESTROY


def decide_for_reaper(now: datetime, expires_at_raw, census_fn: Callable[[], Census]) -> str:
    """Layer 3 (the reaper, FT-3): billable census, 2 h grace. Errors raise to trip its alarm."""
    return decide(now, expires_at_raw, census_fn().billable_only(), grace=REAPER_GRACE)
