package sqlancer.common.oracle.lateral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLGlobalState;
import sqlancer.common.oracle.lateral.LateralQuery.ColumnRef;
import sqlancer.common.oracle.lateral.LateralQuery.Comparison;
import sqlancer.common.oracle.lateral.LateralQuery.ComparisonOperator;
import sqlancer.common.oracle.lateral.LateralQuery.Join;
import sqlancer.common.oracle.lateral.LateralQuery.JoinType;
import sqlancer.common.oracle.lateral.LateralQuery.OrderTerm;
import sqlancer.common.oracle.lateral.LateralQuery.SelectedColumn;
import sqlancer.common.oracle.lateral.LateralQuery.Subquery;
import sqlancer.common.oracle.lateral.LateralQuery.SubqueryTable;
import sqlancer.common.query.ExpectedErrors;

public class TestLateralJsonOracle {

    private static final int QUERY_COUNT = 2000;
    private static final LateralColumnType INTEGER = new LateralColumnType("integer", null, "number");
    private static final LateralColumnType CANONICAL_NUMBER = new LateralColumnType("double", null, "number");
    private static final LateralColumnType TEXT_A = new LateralColumnType("text", "a", "text");
    private static final LateralColumnType TEXT_B = new LateralColumnType("text", "b", "text");

    @Test
    public void randomQueriesFollowTheRulesOfTheOracle() {
        for (LateralQuery query : randomQueries(new FakeOracle(false))) {
            assertTrue(query.getJoins().size() >= 1 && query.getJoins().size() <= 3);
            assertTrue(rowsOfAllTables(query) <= 10_000);
            assertCommasAreAtTheEnd(query);
            for (int position = 0; position < query.getJoins().size(); position++) {
                Subquery subquery = query.getJoins().get(position).getSubquery();
                assertSelectsAColumnOfItsOwnTables(subquery);
                assertLimitSortsByEveryColumn(subquery);
                assertCanonicalColumnsOnlyWithDistinctOrLimit(subquery);
                assertComparisonsUseComparableTypes(query, position);
            }
        }
    }

    @Test
    public void hooksRemoveTheJoinsThatAKnownBugReturnsWrongRowsFor() {
        for (LateralQuery query : randomQueries(new FakeOracle(true))) {
            for (int position = 0; position < query.getJoins().size(); position++) {
                Join join = query.getJoins().get(position);
                Subquery subquery = join.getSubquery();
                for (Comparison comparison : comparisons(subquery)) {
                    assertNotEquals(ComparisonOperator.IS_NOT_DISTINCT_FROM, comparison.getOperator());
                    assertFalse(readsLeftJoinWithOnPredicate(query, comparison.getLeft()));
                    assertFalse(readsLeftJoinWithOnPredicate(query, comparison.getRight()));
                }
                if (join.getType() == JoinType.LEFT) {
                    assertFalse(subquery.isFilterWithOr());
                    assertEquals(1, subquery.getTables().size());
                    Set<String> ownAliases = aliases(subquery);
                    for (SelectedColumn column : subquery.getColumns()) {
                        assertTrue(column.getSource().isTableColumn()
                                && ownAliases.contains(column.getSource().getTableAlias()));
                    }
                }
                for (SelectedColumn column : subquery.getColumns()) {
                    assertFalse(readsLeftJoinWithOnPredicate(query, column.getSource()));
                }
            }
        }
    }

    @Test
    public void lateralFormHasTheSubqueryOfEachJoin() {
        LateralColumnType type = INTEGER;
        Join join = new Join(JoinType.LEFT, "TRUE", "s0",
                new Subquery(
                        Arrays.asList(new SubqueryTable("t1", "i0", new ArrayList<>()),
                                new SubqueryTable("t2", "i0_1",
                                        Arrays.asList(new Comparison(ColumnRef.tableColumn("i0_1", "c0"),
                                                ComparisonOperator.EQUALS, ColumnRef.tableColumn("i0", "c0"))))),
                        true, Arrays.asList(new SelectedColumn(ColumnRef.tableColumn("i0", "c1"), type, true, type)),
                        new Comparison(ColumnRef.tableColumn("i0", "c0"), ComparisonOperator.LESS,
                                ColumnRef.tableColumn("o", "c0")),
                        true, "i0.c1 > 1", Arrays.asList(new OrderTerm(0, true)), 2));
        LateralQuery query = new LateralQuery("t0", "o", Arrays.asList("c0"), Arrays.asList(join), "TRUE");

        assertEquals(
                "SELECT o.c0, s0.v0 FROM t0 AS o LEFT JOIN LATERAL (SELECT DISTINCT canonical(i0.c1) AS v0 "
                        + "FROM t1 AS i0 JOIN t2 AS i0_1 ON (i0_1.c0 = i0.c0) WHERE (i0.c0 < o.c0) OR (i0.c1 > 1) "
                        + "ORDER BY v0 DESC LIMIT 2) AS s0 ON TRUE WHERE TRUE",
                new FakeOracle(false).lateralQuery(query));
    }

    @Test
    public void rowsAreTheSameInAnyOrderButNotWithAMissingCopy() {
        assertTrue(LateralJsonOracle.sameRows(Arrays.asList("(1)", "(2)"), Arrays.asList("(2)", "(1)")));
        assertFalse(LateralJsonOracle.sameRows(Arrays.asList("(1)", "(1)"), Arrays.asList("(1)")));
        assertFalse(LateralJsonOracle.sameRows(Arrays.asList("(1)"), Arrays.asList("(2)")));
    }

    @Test
    public void predicateSelectsARowOrIsTrue() {
        FakeOracle oracle = new FakeOracle(false);
        oracle.rowCounts.put("o.c0 = 1", 2L);
        LateralTable table = table("t0", 3, column("c0", INTEGER));
        Iterator<String> secondSelectsARow = Arrays.asList("o.c0 = 5", "o.c0 = 1", "o.c0 = 6").iterator();
        assertEquals("o.c0 = 1", oracle.predicateThatSelectsARow(table, "o", secondSelectsARow::next));
        Iterator<String> fourthSelectsARow = Arrays.asList("o.c0 = 5", "o.c0 = 6", "o.c0 = 7", "o.c0 = 1").iterator();
        assertEquals("TRUE", oracle.predicateThatSelectsARow(table, "o", fourthSelectsARow::next));
    }

    private static List<LateralQuery> randomQueries(FakeOracle oracle) {
        List<LateralTable> tables = Arrays.asList(table("t0", 3, column("c0", INTEGER, true), column("c1", TEXT_A)),
                table("t1", 5, column("c0", INTEGER, false), column("c1", TEXT_B)),
                table("t2", 0, column("c0", TEXT_A)), table("t3", 40, column("c0", INTEGER, true)));
        List<LateralQuery> queries = new ArrayList<>();
        new Randomly(0);
        while (queries.size() < QUERY_COUNT) {
            try {
                queries.add(new LateralQueryGenerator(oracle, tables).generate());
            } catch (IgnoreMeException e) {
                // the tables have no comparable columns for this join
            }
        }
        return queries;
    }

    private static LateralTable table(String name, long rows, LateralTable.Column... columns) {
        return new LateralTable(name, rows, Arrays.asList(columns));
    }

    private static LateralTable.Column column(String name, LateralColumnType type) {
        return column(name, type, false);
    }

    private static LateralTable.Column column(String name, LateralColumnType type, boolean leadsAnIndex) {
        return new LateralTable.Column(name, type, leadsAnIndex, true);
    }

    private static long rowsOfAllTables(LateralQuery query) {
        Map<String, Long> rows = new HashMap<>();
        rows.put("t0", 3L);
        rows.put("t1", 5L);
        rows.put("t2", 1L);
        rows.put("t3", 40L);
        long product = rows.get(query.getTable());
        for (Join join : query.getJoins()) {
            for (SubqueryTable table : join.getSubquery().getTables()) {
                product *= rows.get(table.getTable());
            }
        }
        return product;
    }

    private static void assertCommasAreAtTheEnd(LateralQuery query) {
        boolean commaSeen = false;
        for (Join join : query.getJoins()) {
            if (join.getType() == JoinType.COMMA) {
                commaSeen = true;
            } else {
                assertFalse(commaSeen, "a join after a comma must be a comma join");
            }
        }
    }

    private static void assertSelectsAColumnOfItsOwnTables(Subquery subquery) {
        Set<String> ownAliases = aliases(subquery);
        assertTrue(subquery.getColumns().stream().anyMatch(column -> column.getSource().isTableColumn()
                && ownAliases.contains(column.getSource().getTableAlias())));
    }

    private static void assertLimitSortsByEveryColumn(Subquery subquery) {
        if (subquery.getLimit() == null) {
            return;
        }
        Set<Integer> sortedColumns = subquery.getOrderBy().stream().map(OrderTerm::getColumn)
                .collect(Collectors.toSet());
        assertEquals(subquery.getColumns().size(), subquery.getOrderBy().size());
        assertEquals(subquery.getColumns().size(), sortedColumns.size());
    }

    private static void assertCanonicalColumnsOnlyWithDistinctOrLimit(Subquery subquery) {
        for (SelectedColumn column : subquery.getColumns()) {
            if (column.isCanonical()) {
                assertTrue(subquery.isDistinct() || subquery.getLimit() != null);
                assertEquals(CANONICAL_NUMBER, column.getType());
            } else {
                assertEquals(column.getSourceType(), column.getType());
            }
        }
    }

    private static void assertComparisonsUseComparableTypes(LateralQuery query, int position) {
        for (Comparison comparison : comparisons(query.getJoins().get(position).getSubquery())) {
            LateralColumnType left = type(query, position, comparison.getLeft());
            LateralColumnType right = type(query, position, comparison.getRight());
            assertTrue(left.getGroup().equals(right.getGroup()) && left.hasSameCollation(right));
        }
    }

    private static List<Comparison> comparisons(Subquery subquery) {
        List<Comparison> comparisons = new ArrayList<>();
        comparisons.add(subquery.getCorrelation());
        for (SubqueryTable table : subquery.getTables()) {
            comparisons.addAll(table.getOn());
        }
        return comparisons;
    }

    private static LateralColumnType type(LateralQuery query, int position, ColumnRef column) {
        if (!column.isTableColumn()) {
            assertTrue(column.getJoin() < position);
            return query.getJoins().get(column.getJoin()).getSubquery().getColumns().get(column.getColumn()).getType();
        }
        String table = query.getTable();
        if (!column.getTableAlias().equals(query.getAlias())) {
            table = query.getJoins().get(position).getSubquery().getTables().stream()
                    .filter(subqueryTable -> subqueryTable.getAlias().equals(column.getTableAlias())).findFirst().get()
                    .getTable();
        }
        Map<String, LateralColumnType> types = new HashMap<>();
        types.put("t0.c0", INTEGER);
        types.put("t0.c1", TEXT_A);
        types.put("t1.c0", INTEGER);
        types.put("t1.c1", TEXT_B);
        types.put("t2.c0", TEXT_A);
        types.put("t3.c0", INTEGER);
        LateralColumnType type = types.get(table + "." + column.getColumnName());
        assertNotNull(type);
        return type;
    }

    private static boolean readsLeftJoinWithOnPredicate(LateralQuery query, ColumnRef column) {
        if (column.isTableColumn()) {
            return false;
        }
        Join join = query.getJoins().get(column.getJoin());
        return join.getType() == JoinType.LEFT && !"TRUE".equals(join.getOnClause());
    }

    private static Set<String> aliases(Subquery subquery) {
        return subquery.getTables().stream().map(SubqueryTable::getAlias)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static final class FakeOracle extends LateralJsonOracle<SQLGlobalState<?, ?>> {

        private final boolean avoidKnownBugs;
        private final Map<String, Long> rowCounts = new HashMap<>();

        FakeOracle(boolean avoidKnownBugs) {
            super(null, new ExpectedErrors());
            this.avoidKnownBugs = avoidKnownBugs;
        }

        @Override
        protected List<LateralTable> getTables() {
            return Collections.emptyList();
        }

        @Override
        protected String predicate(LateralTable table, String alias) {
            return alias + ".c0 IS NOT NULL";
        }

        @Override
        protected long countRows(LateralTable table, String alias, String predicate) {
            return rowCounts.getOrDefault(predicate, 0L);
        }

        @Override
        protected boolean canCompare(LateralColumnType first, LateralColumnType second) {
            return first.getGroup().equals(second.getGroup()) && first.hasSameCollation(second);
        }

        @Override
        protected LateralColumnType canonicalType(LateralColumnType type) {
            return type.getGroup().equals("number") ? CANONICAL_NUMBER : null;
        }

        @Override
        protected String canonicalExpression(String expression, LateralColumnType type) {
            return "canonical(" + expression + ")";
        }

        @Override
        protected String comparison(String left, ComparisonOperator operator, String right) {
            switch (operator) {
            case EQUALS:
                return left + " = " + right;
            case LESS:
                return left + " < " + right;
            default:
                return left + " " + operator + " " + right;
            }
        }

        @Override
        protected String jsonQuery(LateralQuery query, List<Integer> jsonJoins) {
            return "";
        }

        @Override
        protected boolean leftJoinCanSelectOuterColumns() {
            return !avoidKnownBugs;
        }

        @Override
        protected boolean laterJoinsCanReadLeftJoinWithOnPredicate() {
            return !avoidKnownBugs;
        }

        @Override
        protected boolean leftJoinCanReadManyTables() {
            return !avoidKnownBugs;
        }

        @Override
        protected boolean leftJoinCanFilterWithOr() {
            return !avoidKnownBugs;
        }

        @Override
        protected List<ComparisonOperator> comparisonOperators() {
            List<ComparisonOperator> operators = new ArrayList<>(super.comparisonOperators());
            if (avoidKnownBugs) {
                operators.remove(ComparisonOperator.IS_NOT_DISTINCT_FROM);
            }
            return operators;
        }
    }
}
