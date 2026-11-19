-- #291: one sales-ledger entry per sale. OrgSalesCapService.recordSale inserts with
-- ON CONFLICT (channel, source_id) DO NOTHING, so recording the same sale twice (a retry,
-- a reconciliation job) can't count it against the rolling sales cap twice.
--
-- Every writer keys its entry on a unique id (the PaymentIntent of a unique payment_records
-- row, or a box_office_sales id), so duplicates are not expected. Any that exist are double
-- counts: keep the earliest row per (channel, source_id) and delete the other copies, or the
-- constraint below would fail. Rows with a NULL source_id are left alone; the constraint
-- doesn't apply to them.
DELETE FROM org_sales_ledger
 WHERE id IN (
     SELECT id
       FROM (SELECT id,
                    ROW_NUMBER() OVER (PARTITION BY channel, source_id
                                       ORDER BY created_at, id) AS copy_number
               FROM org_sales_ledger
              WHERE source_id IS NOT NULL) ranked
      WHERE copy_number > 1);

ALTER TABLE org_sales_ledger
    ADD CONSTRAINT uk_org_sales_ledger_channel_source UNIQUE (channel, source_id);
