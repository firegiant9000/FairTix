-- Box-office card sales record the seat holds they were created with, so confirming a sale
-- completes exactly its own seats, even while the same staff member has another card sale
-- pending on the event. Before this, the confirm took every ACTIVE hold of the staff member
-- on the event.
--
-- A join table, not a column on seat_holds: the online hold flow and its table stay as they
-- are. A hold belongs to at most one sale. Deleting a sale (its intent could not be created)
-- deletes its links.
--
-- Existing rows: sales pending when this runs have no links. Their confirm falls back to the
-- old match (the staff member's ACTIVE holds on the event). Their holds expire one minute
-- after the intent, so this only matters at deploy time.
CREATE TABLE box_office_sale_holds (
    sale_id UUID NOT NULL REFERENCES box_office_sales(id) ON DELETE CASCADE,
    hold_id UUID NOT NULL REFERENCES seat_holds(id),
    PRIMARY KEY (sale_id, hold_id)
);

CREATE UNIQUE INDEX ux_box_office_sale_holds_hold ON box_office_sale_holds(hold_id);

-- Set when the drawer closes on a card sale with no order. The reader may still have charged
-- the card, so box_office_charges_without_sale keeps listing these sales and shows the flag.
ALTER TABLE box_office_sales ADD COLUMN abandoned_at TIMESTAMPTZ;
