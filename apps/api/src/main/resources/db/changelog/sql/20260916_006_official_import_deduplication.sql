CREATE UNIQUE INDEX official_imports_contract_sha_uq
    ON official_imports (contract_version, raw_sha256);
