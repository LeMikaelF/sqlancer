package sqlancer.postgres.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
import sqlancer.common.oracle.lateral.LateralQuery.SelectedColumn;
import sqlancer.common.oracle.lateral.LateralQuery.Subquery;
import sqlancer.common.oracle.lateral.LateralTable;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresSchema.PostgresColumn;
import sqlancer.postgres.PostgresSchema.PostgresDataType;
import sqlancer.postgres.PostgresSchema.PostgresTable;
import sqlancer.postgres.PostgresVisitor;
import sqlancer.postgres.gen.PostgresCommon;
import sqlancer.postgres.gen.PostgresExpressionGenerator;

/**
 * Compares LATERAL joins with their JSON form, {@code json_to_recordset} over a subquery that collects the rows with
 * {@code json_agg}. The query with the JSON form runs without index scans, hash joins, merge joins and memoization, so
 * that the two forms do not use the same plan. See {@link LateralJsonOracle}.
 */
public class PostgresLateralJsonOracle extends LateralJsonOracle<PostgresGlobalState> {

    private static final List<String> PLANNER_SETTINGS = Arrays.asList("enable_indexscan", "enable_indexonlyscan",
            "enable_bitmapscan", "enable_hashjoin", "enable_mergejoin", "enable_memoize");
    private static final int MAX_PREDICATE_ATTEMPTS = 3;
    private static final String NUMERIC = "numeric";
    private static final String DOUBLE_PRECISION = "double precision";

    private final Map<String, PostgresTable> tablesByName = new HashMap<>();
    private List<LateralTable> tables;

    public PostgresLateralJsonOracle(PostgresGlobalState state) {
        super(state, ExpectedErrors.newErrors().with(PostgresCommon.getCommonExpressionErrors())
                .with(PostgresCommon.getCommonFetchErrors()).with(PostgresCommon.getCommonRangeExpressionErrors())
                .withRegex(PostgresCommon.getCommonExpressionRegexErrors()).build());
    }

    @Override
    protected List<LateralTable> getTables() throws SQLException {
        if (tables == null) {
            List<LateralTable> newTables = new ArrayList<>();
            for (PostgresTable table : state.getSchema().getDatabaseTables()) {
                try {
                    newTables.add(lateralTable(table));
                    tablesByName.put(table.getName(), table);
                } catch (IgnoreMeException e) {
                    // the table cannot be read, for example a view whose query fails
                }
            }
            tables = newTables;
        }
        return tables;
    }

    private LateralTable lateralTable(PostgresTable table) throws SQLException {
        Map<String, PostgresDataType> dataTypes = new HashMap<>();
        for (PostgresColumn column : table.getColumns()) {
            dataTypes.put(column.getName(), column.getType());
        }
        Map<String, LateralColumnType> types = new HashMap<>();
        Set<String> indexedColumns = new HashSet<>();
        try (Statement statement = state.getConnection().createStatement()) {
            try (ResultSet rs = statement.executeQuery(String.format(
                    "SELECT a.attname, format_type(a.atttypid, a.atttypmod), quote_ident(c.collname), "
                            + "a.attcollation <> t.typcollation FROM pg_attribute a "
                            + "JOIN pg_type t ON t.oid = a.atttypid LEFT JOIN pg_collation c ON c.oid = a.attcollation "
                            + "WHERE a.attrelid = '%s'::regclass AND a.attnum > 0 AND NOT a.attisdropped",
                    table.getName()))) {
                while (rs.next()) {
                    String column = rs.getString(1);
                    String collation = rs.getString(3);
                    String name = rs.getString(2);
                    if (rs.getBoolean(4)) {
                        name += " COLLATE " + collation;
                    }
                    if (dataTypes.containsKey(column)) {
                        types.put(column, new LateralColumnType(name, collation, group(dataTypes.get(column))));
                    }
                }
            }
            try (ResultSet rs = statement
                    .executeQuery(String.format(
                            "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid "
                                    + "AND a.attnum = i.indkey[0] WHERE i.indrelid = '%s'::regclass",
                            table.getName()))) {
                while (rs.next()) {
                    indexedColumns.add(rs.getString(1));
                }
            }
        }
        List<LateralTable.Column> columns = new ArrayList<>();
        for (PostgresColumn column : table.getColumns()) {
            LateralColumnType type = types.get(column.getName());
            if (type == null) {
                throw new IgnoreMeException();
            }
            columns.add(
                    new LateralTable.Column(column.getName(), type, indexedColumns.contains(column.getName()), true));
        }
        return new LateralTable(table.getName(), table.getNrRows(state), columns);
    }

    private static String group(PostgresDataType type) {
        switch (type) {
        case INT:
        case DECIMAL:
        case FLOAT:
        case REAL:
            return "number";
        default:
            return type.name();
        }
    }

    @Override
    protected String predicate(LateralTable table, String alias) {
        for (int attempt = 0; attempt < MAX_PREDICATE_ATTEMPTS; attempt++) {
            String predicate = randomPredicate(table, alias);
            if (runsWithoutError("SELECT COUNT(*) FROM " + table.getName() + " AS " + alias + " WHERE " + predicate)) {
                return predicate;
            }
        }
        throw new IgnoreMeException();
    }

    private boolean runsWithoutError(String query) {
        try {
            return new SQLQueryAdapter(query, errors).execute(state);
        } catch (SQLException e) {
            throw new AssertionError(query, e);
        }
    }

    private String randomPredicate(LateralTable table, String alias) {
        PostgresTable original = tablesByName.get(table.getName());
        List<PostgresColumn> columns = original.getColumns().stream()
                .map(column -> new PostgresColumn(column.getName(), column.getType())).collect(Collectors.toList());
        PostgresTable aliasedTable = new PostgresTable(alias, columns, original.getIndexes(), original.getTableType(),
                original.getStatistics(), original.isView(), original.isInsertable());
        columns.forEach(column -> column.setTable(aliasedTable));
        state.setAllowedFunctionTypes(Arrays.asList(PostgresGlobalState.IMMUTABLE, PostgresGlobalState.STABLE));
        try {
            return PostgresVisitor.asString(new PostgresExpressionGenerator(state).setColumns(columns)
                    .generateExpression(PostgresDataType.BOOLEAN));
        } finally {
            state.setDefaultAllowedFunctionTypes();
        }
    }

    @Override
    protected boolean canCompare(LateralColumnType first, LateralColumnType second) {
        return first.getGroup().equals(second.getGroup()) && first.hasSameCollation(second);
    }

    @Override
    protected LateralColumnType canonicalType(LateralColumnType type) {
        if (type.getName().startsWith(NUMERIC)) {
            return new LateralColumnType(NUMERIC, null, type.getGroup());
        }
        if (isFloatingPoint(type)) {
            return new LateralColumnType(DOUBLE_PRECISION, null, type.getGroup());
        }
        return null;
    }

    @Override
    protected String canonicalExpression(String expression, LateralColumnType type) {
        if (type.getName().startsWith(NUMERIC)) {
            return "trim_scale(" + expression + ")";
        }
        return "(" + expression + " + 0)";
    }

    private static boolean isFloatingPoint(LateralColumnType type) {
        return type.getName().equals("real") || type.getName().equals(DOUBLE_PRECISION);
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
            return left + " IS NOT DISTINCT FROM " + right;
        case IS_DISTINCT_FROM:
            return left + " IS DISTINCT FROM " + right;
        default:
            throw new AssertionError(operator);
        }
    }

    @Override
    protected String jsonQuery(LateralQuery query, List<Integer> jsonJoins) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(selectList(query)).append(" FROM ").append(query.getTable()).append(" AS ")
                .append(query.getAlias());
        for (int position = 0; position < query.getJoins().size(); position++) {
            Join join = query.getJoins().get(position);
            if (jsonJoins.contains(position)) {
                sb.append(joinClause(join, jsonTable(join)));
            } else {
                sb.append(lateralJoin(join));
            }
        }
        sb.append(whereClause(query));
        return sb.toString();
    }

    private String jsonTable(Join join) {
        Subquery subquery = join.getSubquery();
        boolean jsonb = Randomly.getBoolean()
                && subquery.getColumns().stream().noneMatch(column -> isFloatingPoint(column.getType()));
        String json = jsonb ? "jsonb" : "json";
        String rows;
        if (subquery.hasDistinctOrderByOrLimit()) {
            rows = "SELECT " + json + "_agg(q) FROM (" + subquery(subquery, "", "") + ") AS q";
        } else {
            List<String> keysAndValues = new ArrayList<>();
            for (int column = 0; column < subquery.getColumns().size(); column++) {
                keysAndValues.add("'" + columnName(column) + "', " + selectedColumn(subquery.getColumns().get(column)));
            }
            rows = "SELECT " + json + "_agg(" + json + "_build_object(" + String.join(", ", keysAndValues) + "))"
                    + fromAndWhere(subquery, "");
        }
        List<String> definitions = new ArrayList<>();
        for (int column = 0; column < subquery.getColumns().size(); column++) {
            SelectedColumn selected = subquery.getColumns().get(column);
            definitions.add(columnName(column) + " " + selected.getType().getName());
        }
        return json + "_to_recordset((" + rows + ")) AS " + join.getAlias() + "(" + String.join(", ", definitions)
                + ")";
    }

    @Override
    protected List<String> runJsonQuery(PostgresGlobalState globalState, String query, int columnCount)
            throws SQLException {
        List<String> previousValues = new ArrayList<>();
        try (Statement statement = globalState.getConnection().createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT " + PLANNER_SETTINGS.stream()
                    .map(setting -> "current_setting('" + setting + "')").collect(Collectors.joining(", ")))) {
                rs.next();
                for (int i = 1; i <= PLANNER_SETTINGS.size(); i++) {
                    previousValues.add(rs.getString(i));
                }
            }
            statement.execute(plannerSettings());
            try {
                return fetchRows(globalState, query, columnCount);
            } finally {
                List<String> restore = new ArrayList<>();
                for (int i = 0; i < PLANNER_SETTINGS.size(); i++) {
                    restore.add("SET " + PLANNER_SETTINGS.get(i) + " = " + previousValues.get(i));
                }
                statement.execute(String.join("; ", restore));
            }
        }
    }

    @Override
    protected String describeJsonQuery(String query) {
        return plannerSettings() + "; " + query;
    }

    private static String plannerSettings() {
        return PLANNER_SETTINGS.stream().map(setting -> "SET " + setting + " = off").collect(Collectors.joining("; "));
    }
}
