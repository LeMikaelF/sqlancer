package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.common.oracle.lateral.LateralColumnType;

public class TestSQLite3LateralJsonOracle {

    private static final LateralColumnType INTEGER_COLUMN = new LateralColumnType("integer", null, "INTEGER");
    private static final LateralColumnType TEXT_COLUMN = new LateralColumnType("text", null, "TEXT");
    private static final LateralColumnType REAL_COLUMN = new LateralColumnType("quoted", null, "REAL");
    private static final LateralColumnType BLOB_COLUMN = new LateralColumnType("quoted", null, "BLOB");

    private final SQLite3LateralJsonOracle oracle = new SQLite3LateralJsonOracle(null);

    @Test
    public void affinityFollowsTheRulesOfSQLite() {
        assertEquals("INTEGER", SQLite3LateralJsonOracle.affinity("INT"));
        assertEquals("INTEGER", SQLite3LateralJsonOracle.affinity("INTEGER GENERATED ALWAYS"));
        assertEquals("TEXT", SQLite3LateralJsonOracle.affinity("VARCHAR(10)"));
        assertEquals("BLOB", SQLite3LateralJsonOracle.affinity("BLOB"));
        assertEquals("BLOB", SQLite3LateralJsonOracle.affinity(""));
        assertEquals("REAL", SQLite3LateralJsonOracle.affinity("REAL"));
        assertEquals("NUMERIC", SQLite3LateralJsonOracle.affinity("NUM"));
    }

    @Test
    public void jsonKeepsOnlyIntegersAndTextsOfTheirOwnAffinity() {
        assertEquals("integer", SQLite3LateralJsonOracle.jsonKind("INTEGER", true, false));
        assertEquals("quoted", SQLite3LateralJsonOracle.jsonKind("INTEGER", false, true));
        assertEquals("text", SQLite3LateralJsonOracle.jsonKind("TEXT", false, true));
        assertEquals("quoted", SQLite3LateralJsonOracle.jsonKind("REAL", true, true));
        assertEquals("quoted", SQLite3LateralJsonOracle.jsonKind("BLOB", true, true));
    }

    @Test
    public void subqueriesReturnValuesThatJsonKeeps() {
        assertEquals("i0.c0", oracle.canonicalExpression("i0.c0", INTEGER_COLUMN));
        assertEquals("(i0.c0 COLLATE BINARY)", oracle.canonicalExpression("i0.c0", TEXT_COLUMN));
        assertEquals("quote(i0.c0)", oracle.canonicalExpression("i0.c0", REAL_COLUMN));
        LateralColumnType lateral = oracle.canonicalType(REAL_COLUMN);
        assertSame(lateral, oracle.canonicalType(lateral));
        assertEquals("(s0.v0 COLLATE BINARY)", oracle.canonicalExpression("(s0.v0 COLLATE BINARY)", lateral));
    }

    @Test
    public void lateralColumnsAreComparedOnlyIfBothFormsGiveTheSameResult() {
        LateralColumnType lateralInteger = oracle.canonicalType(INTEGER_COLUMN);
        LateralColumnType lateralText = oracle.canonicalType(TEXT_COLUMN);
        LateralColumnType lateralQuoted = oracle.canonicalType(REAL_COLUMN);
        assertTrue(oracle.canCompare(TEXT_COLUMN, REAL_COLUMN));
        assertTrue(oracle.canCompare(lateralInteger, REAL_COLUMN));
        assertFalse(oracle.canCompare(lateralInteger, TEXT_COLUMN));
        assertTrue(oracle.canCompare(TEXT_COLUMN, lateralText));
        assertFalse(oracle.canCompare(lateralText, INTEGER_COLUMN));
        assertTrue(oracle.canCompare(lateralQuoted, BLOB_COLUMN));
        assertFalse(oracle.canCompare(lateralQuoted, lateralInteger));
    }

    @Test
    public void anIndexLeadsWithItsFirstColumn() {
        assertEquals("c1", SQLite3LateralJsonOracle.firstIndexColumn("CREATE INDEX i0 ON t0(c1, c0)"));
        assertEquals("c0",
                SQLite3LateralJsonOracle.firstIndexColumn("CREATE UNIQUE INDEX i1 ON t1(c0 COLLATE NOCASE DESC)"));
        assertNull(SQLite3LateralJsonOracle.firstIndexColumn("CREATE INDEX i2 ON t0((c0 + 1))"));
        assertNull(SQLite3LateralJsonOracle.firstIndexColumn("CREATE INDEX i3 ON t0(c0 || c1)"));
    }
}
