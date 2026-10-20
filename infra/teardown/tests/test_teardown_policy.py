"""T1: unit tests for decide() (docs/infra/teardown-design.md §4).

Each test names the TD §4 rule row (TD:124-131) or test case (TD:133-144) it covers.
"""
from datetime import datetime, timedelta, timezone

import pytest

from teardown.teardown_policy import (
    DESTROY,
    NOOP,
    Census,
    Item,
    decide,
    decide_for_layer2,
    decide_for_reaper,
)

UTC = timezone.utc
NOW = datetime(2026, 10, 20, 12, 0, 0, tzinfo=UTC)
H = timedelta(hours=1)
REAPER_GRACE = timedelta(hours=2)


def iso_z(dt):
    return dt.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def stack(age, kind="rds_instance"):
    """A billable lab stack created `age` before NOW."""
    return Census(billable=(Item(kind, "fairtix-lab-db", NOW - age),))


def free_only(age):
    return Census(free=(Item("log_group", "/ecs/fairtix-lab", NOW - age),))


# --- Row 1: census empty -> noop -------------------------------------------------

@pytest.mark.parametrize("raw", [None, "none", "garbage", iso_z(NOW - 10 * H), iso_z(NOW + H)])
def test_row1_empty_census_is_noop_whatever_the_expiry(raw):
    assert decide(NOW, raw, Census()) == NOOP


def test_row1_empty_census_is_noop_with_reaper_grace():
    assert decide(NOW, None, Census(), grace=REAPER_GRACE) == NOOP


# --- Row 2: missing / none / malformed / naive / overflow -> destroy (fail closed) -

@pytest.mark.parametrize(
    "raw",
    [
        None,
        "",
        "none",
        "None",
        "  none  ",
        "not-a-date",
        "2026-13-01T00:00:00Z",
        "2026-10-20T12:00:00",            # no timezone (naive)
        "2026-10-20",                     # date only: naive
        "1",
    ],
)
def test_row2_unusable_expiry_with_resources_destroys(raw):
    assert decide(NOW, raw, stack(10 * 60 * timedelta(seconds=1))) == DESTROY


@pytest.mark.parametrize("raw", [123, 1.5, b"2026-10-20T12:00:00Z", object()])
def test_row2_non_string_expiry_is_malformed_and_destroys(raw):
    assert decide(NOW, raw, stack(H)) == DESTROY


def test_row2_missing_expiry_with_grace_and_unknown_age_destroys_at_once():
    eip_only = Census(billable=(Item("eip", "eipalloc-0abc", None),))
    assert decide(NOW, None, eip_only, grace=REAPER_GRACE) == DESTROY


def test_td142_missing_expiry_grace_2h_30_min_old_stack_is_noop():
    assert decide(NOW, None, stack(timedelta(minutes=30)), grace=REAPER_GRACE) == NOOP


def test_td142_missing_expiry_grace_2h_3_hour_old_stack_destroys():
    assert decide(NOW, None, stack(3 * H), grace=REAPER_GRACE) == DESTROY


def test_row2_missing_expiry_grace_boundary_is_inclusive():
    assert decide(NOW, None, stack(REAPER_GRACE), grace=REAPER_GRACE) == DESTROY
    assert decide(NOW, None, stack(REAPER_GRACE - timedelta(seconds=1)), grace=REAPER_GRACE) == NOOP


def test_row2_malformed_expiry_with_grace_waits_for_grace():
    assert decide(NOW, "garbage", stack(timedelta(minutes=30)), grace=REAPER_GRACE) == NOOP
    assert decide(NOW, "garbage", stack(3 * H), grace=REAPER_GRACE) == DESTROY


# --- Row 3: clamp a far-future expiry to oldest_created + max_ttl ----------------

def test_row3_far_future_expiry_is_clamped_from_oldest_created():
    far = iso_z(NOW + 30 * 24 * H)
    assert decide(NOW, far, stack(11 * H)) == NOOP     # clamped to NOW + 1h
    assert decide(NOW, far, stack(12 * H)) == DESTROY  # clamped to NOW


def test_row3_clamp_is_relative_to_max_ttl():
    far = iso_z(NOW + 30 * 24 * H)
    assert decide(NOW, far, stack(3 * H), max_ttl=4 * H) == NOOP
    assert decide(NOW, far, stack(5 * H), max_ttl=4 * H) == DESTROY


def test_row3_expiry_within_max_ttl_is_not_clamped():
    # expires in 2 h, stack 1 h old: 1 h + 12 h ceiling not reached, so the expiry stands.
    assert decide(NOW, iso_z(NOW + 2 * H), stack(H)) == NOOP


def test_td139_old_free_item_does_not_pull_the_clamp_into_the_past():
    census = Census(
        billable=(Item("ecs_service", "fairtix-lab-api", NOW - H),),
        free=(Item("log_group", "/ecs/fairtix-lab", NOW - 30 * 24 * H),),
    )
    assert census.oldest_created == NOW - H
    assert decide(NOW, iso_z(NOW + 3 * H), census) == NOOP


def test_td141_free_leftovers_only_far_future_is_clamped_from_oldest_free_item():
    census = Census(free=(
        Item("log_group", "/ecs/fairtix-lab", NOW - 13 * H),
        Item("ecr_repository", "fairtix-lab-backend", NOW - H),
    ))
    assert census.oldest_created == NOW - 13 * H
    assert decide(NOW, iso_z(NOW + 30 * 24 * H), census) == DESTROY


def test_oldest_created_ignores_billable_items_without_a_timestamp():
    census = Census(billable=(
        Item("eip", "eipalloc-0abc", None),
        Item("rds_instance", "fairtix-lab-db", NOW - 2 * H),
    ))
    assert census.oldest_created == NOW - 2 * H


def test_oldest_created_is_none_when_billable_items_report_no_time():
    census = Census(
        billable=(Item("eip", "eipalloc-0abc", None),),
        free=(Item("log_group", "/ecs/fairtix-lab", NOW - 30 * 24 * H),),
    )
    assert census.oldest_created is None


# --- Row 4: no oldest_created and expiry > now + max_ttl -> destroy (tampering) ----

def test_td140_eip_only_census_with_far_future_expiry_destroys():
    eip_only = Census(billable=(Item("eip", "eipalloc-0abc", None),))
    assert decide(NOW, iso_z(NOW + 30 * 24 * H), eip_only) == DESTROY


def test_row4_unknown_age_expiry_just_inside_max_ttl_is_noop():
    eip_only = Census(billable=(Item("eip", "eipalloc-0abc", None),))
    assert decide(NOW, iso_z(NOW + 12 * H), eip_only) == NOOP
    assert decide(NOW, iso_z(NOW + 12 * H + timedelta(seconds=1)), eip_only) == DESTROY


# --- Rows 5-6: compare now >= expires_at + grace -----------------------------------

def test_td135_one_second_before_expiry_is_noop():
    assert decide(NOW, iso_z(NOW + timedelta(seconds=1)), stack(H)) == NOOP


def test_td135_at_expiry_destroys():
    assert decide(NOW, iso_z(NOW), stack(H)) == DESTROY


def test_td135_one_second_after_expiry_destroys():
    assert decide(NOW, iso_z(NOW - timedelta(seconds=1)), stack(H)) == DESTROY


def test_row5_grace_delays_the_compare():
    expired_1h_ago = iso_z(NOW - H)
    assert decide(NOW, expired_1h_ago, stack(5 * H), grace=REAPER_GRACE) == NOOP
    assert decide(NOW, iso_z(NOW - REAPER_GRACE), stack(5 * H), grace=REAPER_GRACE) == DESTROY


def test_row6_future_expiry_is_noop():
    assert decide(NOW, iso_z(NOW + 3 * H), stack(H)) == NOOP


# --- TD:136: ISO-8601 with Z, offsets and DST ---------------------------------------

def test_td136_z_suffix_parses_rather_than_reading_as_malformed():
    # A malformed value would destroy; a parsed future value is a noop.
    assert decide(NOW, "2026-10-20T15:00:00Z", stack(H)) == NOOP
    assert decide(NOW, "2026-10-20T15:00:00.123456Z", stack(H)) == NOOP


@pytest.mark.parametrize(
    "raw",
    [
        "2026-10-20T12:00:00Z",
        "2026-10-20T12:00:00+00:00",
        "2026-10-20T08:00:00-04:00",   # US Eastern daylight time
        "2026-10-20T14:00:00+02:00",   # Central European summer time
    ],
)
def test_td136_equal_instants_in_any_offset_compare_equal(raw):
    assert decide(NOW, raw, stack(H)) == DESTROY
    assert decide(NOW - timedelta(seconds=1), raw, stack(H)) == NOOP


def test_td136_now_across_a_dst_change_uses_absolute_time():
    # 2026-11-01 06:00Z is the US fall-back instant; the policy only sees UTC instants.
    expiry = "2026-11-01T01:30:00-05:00"  # == 06:30Z, after fall-back
    now = datetime(2026, 11, 1, 6, 29, 59, tzinfo=UTC)
    census = Census(billable=(Item("rds_instance", "fairtix-lab-db", now - H),))
    assert decide(now, expiry, census) == NOOP
    assert decide(now + timedelta(seconds=1), expiry, census) == DESTROY


# --- TD:138: year 9999 ----------------------------------------------------------------

def test_td138_year_9999_with_unknown_age_destroys():
    eip_only = Census(billable=(Item("eip", "eipalloc-0abc", None),))
    assert decide(NOW, "9999-12-31T23:59:59Z", eip_only) == DESTROY
    assert decide(NOW, "9999-12-31T23:59:59Z", eip_only, grace=REAPER_GRACE) == DESTROY


def test_td138_year_9999_on_a_stack_older_than_max_ttl_destroys():
    assert decide(NOW, "9999-12-31T23:59:59Z", stack(13 * H)) == DESTROY
    assert decide(NOW, "9999-12-31T23:59:59Z", stack(15 * H), grace=REAPER_GRACE) == DESTROY


def test_td138_year_9999_on_a_fresh_stack_is_clamped_not_honoured():
    # Clamped to oldest_created + 12 h: noop now, destroy once the clamp passes.
    raw = "9999-12-31T23:59:59Z"
    assert decide(NOW, raw, stack(H)) == NOOP
    assert decide(NOW + 11 * H, raw, stack(H)) == DESTROY


def test_td138_max_datetime_plus_grace_overflow_destroys():
    raw = "9999-12-31T23:59:59.999999+00:00"
    assert decide(NOW, raw, Census(billable=(Item("eip", "e", None),)), grace=REAPER_GRACE) == DESTROY


def test_td138_extreme_offset_far_future_is_clamped_on_a_fresh_stack():
    raw = "9999-12-31T23:59:59-23:59"  # beyond datetime.max in UTC, still comparable
    assert decide(NOW, raw, stack(H)) == NOOP
    assert decide(NOW, raw, stack(13 * H)) == DESTROY


def test_td122_arithmetic_overflow_counts_as_malformed_and_destroys():
    huge = timedelta(days=999_999_999)  # NOW + max_ttl overflows
    eip_only = Census(billable=(Item("eip", "e", None),))
    assert decide(NOW, iso_z(NOW + H), eip_only, max_ttl=huge) == DESTROY
    # grace overflow on the compare, with a timestamped stack
    assert decide(NOW, iso_z(NOW - H), stack(H), grace=huge) == DESTROY


# --- now is injected and must be timezone-aware ------------------------------------

def test_naive_now_is_rejected():
    with pytest.raises(ValueError):
        decide(NOW.replace(tzinfo=None), iso_z(NOW + H), stack(H))


def test_decide_has_no_wall_clock(monkeypatch):
    import teardown.teardown_policy as policy

    class Boom:
        fromisoformat = staticmethod(datetime.fromisoformat)

        @staticmethod
        def now(*_a, **_k):
            raise AssertionError("decide() read the wall clock")

        utcnow = today = now

    monkeypatch.setattr(policy, "datetime", Boom, raising=False)
    # A fixed, far-past `now` with a far-past expiry: decided purely on the inputs.
    past = datetime(2001, 1, 1, tzinfo=UTC)
    census = Census(billable=(Item("rds_instance", "fairtix-lab-db", past - H),))
    assert decide(past, "2001-01-01T02:00:00Z", census) == NOOP


# --- Census classification: unknown kinds fail closed -------------------------------

def test_census_from_items_splits_billable_and_free():
    census = Census.from_items([
        Item("rds_instance", "fairtix-lab-db", None),
        Item("log_group", "/ecs/fairtix-lab", None),
    ])
    assert [i.kind for i in census.billable] == ["rds_instance"]
    assert [i.kind for i in census.free] == ["log_group"]


def test_census_unknown_kind_counts_as_billable():
    census = Census.from_items([Item("some_new_service", "x", None)])
    assert len(census.billable) == 1
    assert decide(NOW, iso_z(NOW + 3 * H), census.billable_only()) == NOOP  # counted, not ignored
    assert decide(NOW, None, census.billable_only()) == DESTROY


def test_billable_only_drops_free_items():
    census = Census(billable=(Item("eip", "e", None),), free=(Item("vpc", "vpc-1", None),))
    assert census.billable_only() == Census(billable=(Item("eip", "e", None),))


# --- Callers: layer 2 and the reaper -------------------------------------------------

class Throttled(Exception):
    pass


def failing_census():
    raise Throttled("Rate exceeded")


def test_td137_census_error_layer2_destroys():
    assert decide_for_layer2(NOW, iso_z(NOW + 3 * H), failing_census) == DESTROY


def test_td137_census_error_reaper_raises():
    with pytest.raises(Throttled):
        decide_for_reaper(NOW, iso_z(NOW + 3 * H), failing_census)


def test_layer2_any_exception_inside_decide_destroys():
    # A naive now is a caller bug; layer 2 still fails closed rather than noop.
    assert decide_for_layer2(NOW.replace(tzinfo=None), None, lambda: stack(H)) == DESTROY


def test_td143_bootstrap_only_account_is_an_empty_census_and_noop():
    # The builder drops allow-listed bootstrap items (see test_census.py); what reaches
    # decide() is an empty census, so an empty lab doesn't loop forever.
    assert decide_for_layer2(NOW, "none", lambda: Census()) == NOOP
    assert decide_for_reaper(NOW, "none", lambda: Census()) == NOOP


def test_td144_free_only_past_expiry_layer2_destroys_reaper_noops():
    census = free_only(5 * H)
    expired = iso_z(NOW - 3 * H)
    assert decide_for_layer2(NOW, expired, lambda: census) == DESTROY
    assert decide_for_reaper(NOW, expired, lambda: census) == NOOP


def test_reaper_uses_two_hour_grace():
    expired_1h = iso_z(NOW - H)
    assert decide_for_reaper(NOW, expired_1h, lambda: stack(5 * H)) == NOOP
    assert decide_for_reaper(NOW, iso_z(NOW - 2 * H), lambda: stack(5 * H)) == DESTROY


def test_layer2_uses_no_grace():
    assert decide_for_layer2(NOW, iso_z(NOW), lambda: stack(H)) == DESTROY
