FORMULA_PREFIXES = ("=", "+", "-", "@", "\t", "\r")


def safe_csv_cell(value: object) -> object:
    """Neutralize spreadsheet formulas while preserving ordinary numeric values."""
    if not isinstance(value, str):
        return value
    return f"'{value}" if value.startswith(FORMULA_PREFIXES) else value
