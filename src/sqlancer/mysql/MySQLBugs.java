package sqlancer.mysql;

// do not make the fields final to avoid warnings
public final class MySQLBugs {

    // https://bugs.mysql.com/99182 BETWEEN malfunctions for DECIMAL and TEXT
    public static boolean bug99182 = true;

    // https://bugs.mysql.com/bug.php?id=99183
    public static boolean bug99183 = true;

    // https://bugs.mysql.com/bug.php?id=95894
    public static boolean bug95894 = true;

    // https://bugs.mysql.com/bug.php?id=99135
    public static boolean bug99135 = true;

    // https://bugs.mysql.com/bug.php?id=111471
    public static boolean bug111471 = true;

    // https://bugs.mysql.com/bug.php?id=112242
    public static boolean bug112242 = true;

    // https://bugs.mysql.com/bug.php?id=112243
    public static boolean bug112243 = true;

    // https://bugs.mysql.com/bug.php?id=112264
    public static boolean bug112264 = true;

    // https://bugs.mysql.com/bug.php?id=114533
    public static boolean bug114533 = true;

    // https://bugs.mysql.com/bug.php?id=114534
    public static boolean bug114534 = true;

    // https://bugs.mysql.com/bug.php?id=120710
    // Inserting a NULL and a value which rounds to 0 into a DECIMAL column causes result set mismatch.
    public static boolean bug120710 = true;

    // https://bugs.mysql.com/bug.php?id=120711
    // Creating an index on an integer-type column, then inserting a value which rounds to 1, causes result set
    // mismatch.
    public static boolean bug120711 = true;

    // https://bugs.mysql.com/bug.php?id=120712
    // Creating an index in between two NULL inserts causes inconsistent CERT result.
    public static boolean bug120712 = true;

    // https://bugs.mysql.com/bug.php?id=120995
    // MySQL does not treat this as a bug. A comparison of a FLOAT column with a string can give a different result if
    // MySQL reads the FLOAT column with an index:
    // SELECT 1 FROM t1, t0 WHERE IF(t1.c, t1.c, '') IN (t0.c);
    public static boolean bug120995 = true;

    // Not reported yet. If a join compares the same two columns with both = and <=>, and MySQL reads the second table
    // with an index on the column, the join returns the rows where the two columns are NULL:
    // CREATE TABLE t0(c0 INT, UNIQUE KEY(c0));
    // INSERT INTO t0 VALUES (NULL), (0);
    // SELECT * FROM t0 AS a JOIN t0 AS b ON a.c0 = b.c0 AND a.c0 <=> b.c0; -- returns (NULL, NULL) and (0, 0)
    public static boolean bugEqualsAndNullSafeEquals = true;

    // Not reported yet. If a LEFT JOIN LATERAL subquery returns a column of the outer query, and MySQL merges the
    // subquery into the outer query, a reference to this column is not NULL when the subquery has no row:
    // CREATE TABLE t(c0 INT);
    // INSERT INTO t VALUES (1), (2);
    // SELECT o.c0, s.v FROM t AS o LEFT JOIN LATERAL (SELECT o.c0 AS v FROM t AS i WHERE i.c0 > 5) AS s ON TRUE
    // WHERE s.v IS NULL; -- returns no rows, but must return (1, NULL) and (2, NULL)
    public static boolean bugLeftJoinLateralOuterColumn = true;

    // Not reported yet. If the ON clause of a LEFT JOIN is always false, a later LEFT JOIN that compares a column with
    // a column of this join and with a column of an earlier table returns rows that do not match:
    // CREATE TABLE t(c0 INT);
    // INSERT INTO t VALUES (1), (2);
    // SELECT o.c0, s0.c0, i.c0 FROM t AS o LEFT JOIN t AS s0 ON FALSE
    // LEFT JOIN t AS i ON i.c0 = o.c0 AND i.c0 = s0.c0;
    // -- returns (1, NULL, 1) and (2, NULL, 2), but must return (1, NULL, NULL) and (2, NULL, NULL)
    public static boolean bugLeftJoinOnFalseEquality = true;

    // Not reported yet. A regression in MySQL 26.7.0: if the ON clause of a LEFT JOIN compares two columns with = and
    // has an OR with a constant that is always false, MySQL does not apply the comparison:
    // CREATE TABLE t(c0 INT);
    // INSERT INTO t VALUES (1), (2), (NULL);
    // SELECT COUNT(i.c0) FROM t AS o CROSS JOIN t AS s
    // LEFT JOIN t AS i ON (s.c0 = i.c0) AND ((o.c0 = i.c0) OR FALSE); -- returns 6, but must return 2
    public static boolean bugLeftJoinEqualityOrFalse = true;

    // Not reported yet. A regression in MySQL 26.7.0: if a LEFT JOIN joins two tables and its ON clause has a
    // condition on the second table, MySQL applies the condition to the first table when a later LEFT JOIN compares
    // the first table with the outer table:
    // CREATE TABLE t(c0 INT);
    // INSERT INTO t VALUES (NULL), (1);
    // SELECT COUNT(*) FROM t AS o JOIN t AS i0 ON i0.c0 = o.c0
    // LEFT JOIN (t AS i1 JOIN t AS i2 ON TRUE) ON o.c0 = i2.c0 AND i2.c0 > 0
    // LEFT JOIN t AS i3 ON i1.c0 = o.c0 AND i3.c0 = o.c0; -- returns 1, but must return 2
    public static boolean bugNestedLeftJoinConditionOnWrongTable = true;

    private MySQLBugs() {
    }

}
