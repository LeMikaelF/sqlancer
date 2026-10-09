package sqlancer.mysql.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.lateral.LateralColumnType;
import sqlancer.common.oracle.lateral.LateralJsonOracle;
import sqlancer.common.oracle.lateral.LateralQuery;
import sqlancer.common.oracle.lateral.LateralQuery.ComparisonOperator;
import sqlancer.common.oracle.lateral.LateralQuery.Join;
import sqlancer.common.oracle.lateral.LateralQuery.JoinType;
import sqlancer.common.oracle.lateral.LateralQuery.SelectedColumn;
import sqlancer.common.oracle.lateral.LateralQuery.Subquery;
import sqlancer.common.oracle.lateral.LateralTable;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.mysql.MySQLBugs;
import sqlancer.mysql.MySQLErrors;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.gen.MySQLExpressionGenerator;

/**
 * Compares LATERAL joins with their JSON form, {@code JSON_TABLE} over a subquery that collects the rows with
 * {@code JSON_ARRAYAGG}. MySQL does not accept a subquery as the argument of {@code JSON_TABLE}. Thus the JSON form
 * computes the JSON array in one of two places:
 *
 * <ul>
 * <li>In a derived table that replaces the outer table: {@code (SELECT o.*, (SELECT JSON_ARRAYAGG(...) ...) AS j0
 * FROM t0 AS o) AS o}. This is possible only if the subquery reads no earlier LATERAL join.</li>
 * <li>In a LATERAL derived table that always returns one row:
 * {@code CROSS JOIN LATERAL (SELECT JSON_ARRAYAGG(...) AS j ...) AS a0}.</li>
 * </ul>
 *
 * The subqueries of the JSON form read their tables without indexes and without hash joins, so that the two forms do
 * not use the same plan. See {@link LateralJsonOracle}.
 */
public class MySQLLateralJsonOracle extends LateralJsonOracle<MySQLGlobalState> {

    private static final String SELECT_HINT = "/*+ NO_BNL() */ ";
    private static final String TABLE_HINT = " USE INDEX ()";
    private static final String STRING = "string";
    private static final String NUMBER = "number";
    static final String FLOATING_POINT_IN_HASH_INDEX = "floating point in a hash index";
    private static final String CANONICAL_STRING_TYPE = "longtext CHARACTER SET utf8mb4";
    private static final String NO_PAD_BINARY_COLLATION = "utf8mb4_0900_bin";

    private final Map<String, MySQLTable> tablesByName = new HashMap<>();
    private List<LateralTable> tables;

    public MySQLLateralJsonOracle(MySQLGlobalState state) {
        super(state, ExpectedErrors.newErrors().with(MySQLErrors.getExpressionErrors())
                .withRegex(MySQLErrors.getExpressionRegexErrors()).with("Out of sort memory").build());
    }

    @Override
    protected List<LateralTable> getTables() throws SQLException {
        if (tables == null) {
            Map<String, LateralColumnType> types = new HashMap<>();
            Set<String> selectableColumns = new HashSet<>();
            Set<String> indexedColumns = new HashSet<>();
            Set<String> hashIndexColumns = new HashSet<>();
            try (Statement statement = state.getConnection().createStatement()) {
                try (ResultSet rs = statement.executeQuery(String.format(
                        "SELECT TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX, INDEX_TYPE FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = '%s' AND COLUMN_NAME IS NOT NULL",
                        state.getDatabaseName()))) {
                    while (rs.next()) {
                        String key = rs.getString(1) + "." + rs.getString(2);
                        if (rs.getInt(3) == 1) {
                            indexedColumns.add(key);
                        }
                        if ("HASH".equals(rs.getString(4))) {
                            hashIndexColumns.add(key);
                        }
                    }
                }
                try (ResultSet rs = statement.executeQuery(String.format(
                        "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME "
                                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '%s'",
                        state.getDatabaseName()))) {
                    while (rs.next()) {
                        String key = rs.getString(1) + "." + rs.getString(2);
                        String type = rs.getString(3);
                        types.put(key,
                                columnType(type, rs.getString(4), rs.getString(5), hashIndexColumns.contains(key)));
                        if (!type.contains("zerofill")) {
                            selectableColumns.add(key);
                        }
                    }
                }
            }
            List<LateralTable> newTables = new ArrayList<>();
            for (MySQLTable table : state.getSchema().getDatabaseTables()) {
                List<LateralTable.Column> columns = new ArrayList<>();
                for (MySQLColumn column : table.getColumns()) {
                    String key = table.getName() + "." + column.getName();
                    if (!types.containsKey(key)) {
                        throw new IgnoreMeException();
                    }
                    columns.add(new LateralTable.Column(column.getName(), types.get(key), indexedColumns.contains(key),
                            selectableColumns.contains(key)));
                }
                try {
                    newTables.add(new LateralTable(table.getName(), table.getNrRows(state), columns));
                    tablesByName.put(table.getName(), table);
                } catch (IgnoreMeException e) {
                    // the table cannot be read
                }
            }
            tables = newTables;
        }
        return tables;
    }

    static LateralColumnType columnType(String type, String characterSet, String collation, boolean inHashIndex) {
        if (characterSet != null) {
            return new LateralColumnType(type + " CHARACTER SET " + characterSet, collation, STRING);
        }
        LateralColumnType number = new LateralColumnType(type, null, NUMBER);
        if (MySQLBugs.bug67978 && inHashIndex && isFloatingPoint(number)) {
            return new LateralColumnType(type, null, FLOATING_POINT_IN_HASH_INDEX);
        }
        return number;
    }

    @Override
    protected String predicate(LateralTable table, String alias) {
        return predicateThatSelectsARow(table, alias, () -> randomPredicate(table, alias));
    }

    private String randomPredicate(LateralTable table, String alias) {
        MySQLTable original = tablesByName.get(table.getName());
        List<MySQLColumn> columns = original.getColumns().stream().map(column -> new MySQLColumn(column.getName(),
                column.getType(), column.isPrimaryKey(), column.getPrecision(), column.getScale()))
                .collect(Collectors.toList());
        MySQLTable aliasedTable = new MySQLTable(alias, columns, original.getIndexes(), original.getEngine());
        columns.forEach(column -> column.setTable(aliasedTable));
        return MySQLVisitor.asString(new MySQLExpressionGenerator(state).setColumns(columns).generateExpression());
    }

    @Override
    protected boolean canCompare(LateralColumnType first, LateralColumnType second) {
        if (MySQLBugs.bug120995 && (isFloat(first) || isFloat(second))) {
            return false;
        }
        if (first.getGroup().equals(FLOATING_POINT_IN_HASH_INDEX)
                || second.getGroup().equals(FLOATING_POINT_IN_HASH_INDEX)) {
            return false;
        }
        return first.getGroup().equals(second.getGroup()) && first.hasSameCollation(second);
    }

    private static boolean isFloat(LateralColumnType type) {
        return type.getName().startsWith("float");
    }

    @Override
    protected LateralColumnType canonicalType(LateralColumnType type) {
        if (type.hasCollation()) {
            return new LateralColumnType(CANONICAL_STRING_TYPE, NO_PAD_BINARY_COLLATION, STRING);
        }
        if (isFloatingPoint(type)) {
            return new LateralColumnType("double", null, NUMBER);
        }
        return null;
    }

    @Override
    protected String canonicalExpression(String expression, LateralColumnType type) {
        if (type.hasCollation()) {
            return "(CONVERT(" + expression + " USING utf8mb4) COLLATE " + NO_PAD_BINARY_COLLATION + ")";
        }
        return "(" + expression + " + 0)";
    }

    private static boolean isFloatingPoint(LateralColumnType type) {
        return type.getName().startsWith("float") || type.getName().startsWith("double");
    }

    @Override
    protected boolean leftJoinCanSelectOuterColumns() {
        return !MySQLBugs.bugLeftJoinLateralOuterColumn;
    }

    @Override
    protected boolean laterJoinsCanReadLeftJoinWithOnPredicate() {
        return !MySQLBugs.bugLeftJoinOnFalseEquality;
    }

    @Override
    protected boolean leftJoinCanReadManyTables() {
        return !MySQLBugs.bugNestedLeftJoinConditionOnWrongTable;
    }

    @Override
    protected boolean leftJoinCanFilterWithOr() {
        return !MySQLBugs.bugLeftJoinEqualityOrFalse;
    }

    @Override
    protected List<ComparisonOperator> comparisonOperators() {
        List<ComparisonOperator> operators = new ArrayList<>(super.comparisonOperators());
        if (MySQLBugs.bugEqualsAndNullSafeEquals) {
            operators.remove(ComparisonOperator.IS_NOT_DISTINCT_FROM);
        }
        return operators;
    }

    @Override
    protected String comparison(String left, ComparisonOperator operator, String right) {
        switch (operator) {
        case EQUALS:
            return left + " = " + right;
        case NOT_EQUALS:
            return left + " <> " + right;
        case LESS:
            return left + " < " + right;
        case LESS_EQUALS:
            return left + " <= " + right;
        case GREATER:
            return left + " > " + right;
        case GREATER_EQUALS:
            return left + " >= " + right;
        case IS_NOT_DISTINCT_FROM:
            return left + " <=> " + right;
        case IS_DISTINCT_FROM:
            return "NOT (" + left + " <=> " + right + ")";
        default:
            throw new AssertionError(operator);
        }
    }

    @Override
    protected String jsonQuery(LateralQuery query, List<Integer> jsonJoins) {
        boolean[] objects = new boolean[query.getJoins().size()];
        List<Integer> derivedTableJoins = new ArrayList<>();
        for (int position = 0; position < objects.length; position++) {
            objects[position] = Randomly.getBoolean();
            if (jsonJoins.contains(position) && query.getJoins().get(position).getSubquery().readsOnlyTables()
                    && Randomly.getBoolean()) {
                derivedTableJoins.add(position);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(selectList(query)).append(" FROM ");
        if (derivedTableJoins.isEmpty()) {
            sb.append(query.getTable());
        } else {
            sb.append("(SELECT ").append(query.getAlias()).append(".*");
            for (int position : derivedTableJoins) {
                sb.append(", (").append(jsonArray(query.getJoins().get(position).getSubquery(), objects[position], ""))
                        .append(") AS j").append(position);
            }
            sb.append(" FROM ").append(query.getTable()).append(" AS ").append(query.getAlias()).append(")");
        }
        sb.append(" AS ").append(query.getAlias());
        for (int position = 0; position < query.getJoins().size(); position++) {
            Join join = query.getJoins().get(position);
            if (derivedTableJoins.contains(position)) {
                sb.append(joinClause(join, jsonTable(query.getAlias() + ".j" + position, join, objects[position])));
            } else if (jsonJoins.contains(position)) {
                String arrayAlias = "a" + position;
                sb.append(join.getType() == JoinType.COMMA ? ", " : " CROSS JOIN ");
                sb.append("LATERAL (").append(jsonArray(join.getSubquery(), objects[position], " AS j")).append(") AS ")
                        .append(arrayAlias);
                sb.append(joinClause(join, jsonTable(arrayAlias + ".j", join, objects[position])));
            } else {
                sb.append(lateralJoin(join));
            }
        }
        sb.append(whereClause(query));
        return sb.toString();
    }

    private String jsonArray(Subquery subquery, boolean objects, String arrayAlias) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < subquery.getColumns().size(); column++) {
            if (subquery.hasDistinctOrderByOrLimit()) {
                values.add("q." + columnName(column));
            } else {
                values.add(selectedColumn(subquery.getColumns().get(column)));
            }
        }
        String row;
        if (objects) {
            List<String> keysAndValues = new ArrayList<>();
            for (int column = 0; column < values.size(); column++) {
                keysAndValues.add("'" + columnName(column) + "', " + values.get(column));
            }
            row = "JSON_OBJECT(" + String.join(", ", keysAndValues) + ")";
        } else {
            row = "JSON_ARRAY(" + String.join(", ", values) + ")";
        }
        String aggregate = "SELECT " + SELECT_HINT + "JSON_ARRAYAGG(" + row + ")" + arrayAlias;
        if (subquery.hasDistinctOrderByOrLimit()) {
            return aggregate + " FROM (" + subquery(subquery, SELECT_HINT, TABLE_HINT) + ") AS q";
        }
        return aggregate + fromAndWhere(subquery, TABLE_HINT);
    }

    private static String jsonTable(String array, Join join, boolean objects) {
        List<SelectedColumn> columns = join.getSubquery().getColumns();
        List<String> definitions = new ArrayList<>();
        for (int column = 0; column < columns.size(); column++) {
            String path = objects ? "$." + columnName(column) : "$[" + column + "]";
            definitions.add(
                    columnName(column) + " " + declaration(columns.get(column).getType()) + " PATH '" + path + "'");
        }
        return "JSON_TABLE(" + array + ", '$[*]' COLUMNS (" + String.join(", ", definitions) + ")) AS "
                + join.getAlias();
    }

    private static String declaration(LateralColumnType type) {
        if (type.hasCollation()) {
            return type.getName() + " COLLATE " + type.getCollation();
        }
        return type.getName();
    }
}
