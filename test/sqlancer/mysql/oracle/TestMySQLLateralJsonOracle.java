package sqlancer.mysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.common.oracle.lateral.LateralColumnType;
import sqlancer.common.oracle.lateral.LateralQuery.ComparisonOperator;

public class TestMySQLLateralJsonOracle {

    private static final LateralColumnType INT = new LateralColumnType("int", null, "number");
    private static final LateralColumnType DECIMAL = new LateralColumnType("decimal(10,0) unsigned", null, "number");
    private static final LateralColumnType FLOAT = new LateralColumnType("float", null, "number");
    private static final LateralColumnType TEXT = new LateralColumnType("longtext CHARACTER SET utf8mb4",
            "utf8mb4_0900_ai_ci", "string");
    private static final LateralColumnType BINARY_TEXT = new LateralColumnType("longtext CHARACTER SET utf8mb4",
            "utf8mb4_0900_bin", "string");
    private static final LateralColumnType LATIN1_TEXT = new LateralColumnType("tinytext CHARACTER SET latin1",
            "latin1_swedish_ci", "string");

    private final MySQLLateralJsonOracle oracle = new MySQLLateralJsonOracle(null);

    @Test
    public void comparesOnlyValuesOfTheSameKind() {
        assertTrue(oracle.canCompare(INT, DECIMAL));
        assertTrue(oracle.canCompare(TEXT, TEXT));
        assertFalse(oracle.canCompare(DECIMAL, TEXT));
        assertFalse(oracle.canCompare(TEXT, INT));
        assertFalse(oracle.canCompare(TEXT, BINARY_TEXT));
        assertFalse(oracle.canCompare(FLOAT, FLOAT));
    }

    @Test
    public void canonicalFormMakesEqualValuesLookTheSame() {
        assertEquals("utf8mb4_0900_bin", oracle.canonicalType(TEXT).getCollation());
        assertEquals("(CONVERT(s0.v0 USING utf8mb4) COLLATE utf8mb4_0900_bin)",
                oracle.canonicalExpression("s0.v0", TEXT));
        assertEquals("longtext CHARACTER SET utf8mb4", oracle.canonicalType(LATIN1_TEXT).getName());
        assertEquals("utf8mb4_0900_bin", oracle.canonicalType(LATIN1_TEXT).getCollation());
        assertEquals("(CONVERT(s0.v0 USING utf8mb4) COLLATE utf8mb4_0900_bin)",
                oracle.canonicalExpression("s0.v0", LATIN1_TEXT));
        assertEquals("double", oracle.canonicalType(FLOAT).getName());
        assertEquals("(s0.v0 + 0)", oracle.canonicalExpression("s0.v0", FLOAT));
        assertNull(oracle.canonicalType(INT));
    }

    @Test
    public void nullSafeEqualityIsNotUsedWhileTheBugIsOpen() {
        assertFalse(oracle.comparisonOperators().contains(ComparisonOperator.IS_NOT_DISTINCT_FROM));
        assertEquals("NOT (a <=> b)", oracle.comparison("a", ComparisonOperator.IS_DISTINCT_FROM, "b"));
    }
}
