from heatroute.services.exports import safe_csv_cell


def test_csv_cells_neutralize_spreadsheet_formulas() -> None:
    for value in ("=1+1", "+cmd", "-2+3", "@SUM(A1:A2)", "\tformula", "\rformula"):
        assert safe_csv_cell(value) == f"'{value}"

    assert safe_csv_cell("ordinary text") == "ordinary text"
    assert safe_csv_cell(42) == 42
