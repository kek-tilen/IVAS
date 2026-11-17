CREATE TABLE invoice_record (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    invoice_number VARCHAR(100) NOT NULL,
    supplier_name VARCHAR(200) NOT NULL,
    supplier_country VARCHAR(2) NOT NULL,
    supplier_vat_id VARCHAR(40),
    supplier_registry_id VARCHAR(40),
    issue_date DATE NOT NULL,
    due_date DATE,
    currency VARCHAR(3) NOT NULL,
    net_total NUMERIC(16,3) NOT NULL CHECK (net_total >= 0),
    tax_total NUMERIC(16,3) NOT NULL CHECK (tax_total >= 0),
    gross_total NUMERIC(16,3) NOT NULL CHECK (gross_total >= 0),
    note VARCHAR(2000) NOT NULL DEFAULT '',
    handling_status VARCHAR(20) NOT NULL DEFAULT 'NEW'
        CHECK (handling_status IN ('NEW', 'IN_REVIEW', 'RESOLVED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX invoice_created_idx ON invoice_record (created_at DESC, id);

CREATE TABLE invoice_line (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoice_record(id) ON DELETE CASCADE,
    line_position INTEGER NOT NULL CHECK (line_position >= 0),
    description VARCHAR(200) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL CHECK (quantity > 0),
    unit_net_price NUMERIC(18,4) NOT NULL CHECK (unit_net_price >= 0),
    UNIQUE (invoice_id, line_position)
);

-- Stage 1 prepares history storage. Verification execution is connected in stage 3.
-- Snapshots preserve what was checked even if editable invoice data is added later.
CREATE TABLE verification_run (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoice_record(id),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    assessment VARCHAR(40),
    invoice_snapshot TEXT NOT NULL
);
CREATE INDEX verification_invoice_idx ON verification_run (invoice_id, started_at DESC);

CREATE TABLE verification_check (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    code VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PASS', 'FAIL', 'UNKNOWN', 'NOT_APPLICABLE')),
    source VARCHAR(100) NOT NULL,
    source_url TEXT,
    fetched_at TIMESTAMP WITH TIME ZONE,
    message TEXT NOT NULL
);
CREATE INDEX verification_check_run_idx ON verification_check (run_id);
