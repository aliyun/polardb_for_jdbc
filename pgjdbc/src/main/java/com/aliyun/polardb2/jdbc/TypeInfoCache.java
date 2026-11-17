/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static com.aliyun.polardb2.util.internal.Nullness.castNonNull;

import com.aliyun.polardb2.PGCompositeField;
import com.aliyun.polardb2.core.BaseConnection;
import com.aliyun.polardb2.core.BaseStatement;
import com.aliyun.polardb2.core.Oid;
import com.aliyun.polardb2.core.QueryExecutor;
import com.aliyun.polardb2.core.ServerVersion;
import com.aliyun.polardb2.core.TypeInfo;
import com.aliyun.polardb2.util.GT;
import com.aliyun.polardb2.util.PGobject;
import com.aliyun.polardb2.util.PSQLException;
import com.aliyun.polardb2.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TypeInfoCache implements TypeInfo {

  private static final Logger LOGGER = Logger.getLogger(TypeInfoCache.class.getName());

  // pgname (String) -> java.sql.Types (Integer)
  private Map<String, Integer> pgNameToSQLType;

  private Map<Integer, Integer> oidToSQLType;

  // pgname (String) -> java class name (String)
  // ie "text" -> "java.lang.String"
  private Map<String, String> pgNameToJavaClass;

  // oid (Integer) -> pgname (String)
  private Map<Integer, String> oidToPgName;
  // pgname (String) -> oid (Integer)
  private Map<String, Integer> pgNameToOid;

  private Map<String, Integer> javaArrayTypeToOid;

  // pgname (String) -> extension pgobject (Class)
  private Map<String, Class<? extends PGobject>> pgNameToPgObject;

  // type array oid -> base type's oid
  private Map<Integer, Integer> pgArrayToPgType;

  // array type oid -> base type array element delimiter
  private Map<Integer, Character> arrayOidToDelimiter;

  // POLAR: oid -> pg_type.typcategory (e.g. 'L' = associative array / INDEX BY,
  // 'K' = nested table / TABLE OF, 'J' = VARRAY, 'A' = std PG array, 'C' = composite).
  // '\0' is used as a negative-cache marker ("queried but unknown").
  private Map<Integer, Character> oidToTypCategory;

  // POLAR: SqlType -> OID in postgres
  private Map<Integer, Integer> sqlTypeToOid;

  private final BaseConnection conn;
  private final int unknownLength;
  private @Nullable PreparedStatement getOidStatementSimple;
  private @Nullable PreparedStatement getOidStatementComplexNonArray;
  private @Nullable PreparedStatement getOidStatementComplexArray;
  // POLAR: prepared statements for 3-part (schema.pkg.type) and package-aware 2-part lookups
  private @Nullable PreparedStatement getOidStatementPkgNonArray;
  private @Nullable PreparedStatement getOidStatementPkgArray;
  private @Nullable PreparedStatement getOidStatementDottedNonArray;
  private @Nullable PreparedStatement getOidStatementDottedArray;
  private @Nullable PreparedStatement getOidStatementNoMatch;
  private @Nullable PreparedStatement checkPackageNamespaceStatement;
  private @Nullable Boolean packageNamespaceSupported;
  private @Nullable PreparedStatement getNameStatement;
  private @Nullable PreparedStatement getArrayElementOidStatement;
  private @Nullable PreparedStatement getArrayElementByTyparrayStatement;
  private @Nullable PreparedStatement getArrayDelimiterStatement;
  private @Nullable PreparedStatement getTypeInfoStatement;
  private @Nullable PreparedStatement getAllTypeInfoStatement;
  private @Nullable PreparedStatement findCollectionByElementStmt;
  // POLAR: prepared statement for SELECT typcategory FROM pg_type WHERE oid = ?
  private @Nullable PreparedStatement getTypeCategoryStatement;
  // POLAR: prepared statements for synonym resolution
  private @Nullable PreparedStatement resolveSynonymStatement;
  private @Nullable PreparedStatement resolveSynonymQualifiedStatement;
  private @Nullable PreparedStatement checkAllSynonymsStatement;
  private boolean allSynonymsPresent;
  private boolean synonymLookupSupported = true; // set to false on structural sys.all_synonyms errors
  private static final int MAX_SYNONYM_DEPTH = 4;
  // POLAR: composite type OID -> field type OIDs (ordered by attnum). Cache for
  // getCompositeFieldTypeOids so composite record fields can be type-restored.
  private final Map<Integer, int @Nullable []> oidToCompositeFieldOids =
      new ConcurrentHashMap<Integer, int @Nullable []>();
  private @Nullable PreparedStatement getCompositeFieldOidsStatement;
  // POLAR: composite type OID -> field descriptions (attname/atttypid/attnum, ordered by
  // attnum). Cache for getCompositeFieldDescriptions. An empty list marks "no fields".
  private final Map<Integer, List<PGCompositeField>> oidToCompositeFields =
      new ConcurrentHashMap<Integer, List<PGCompositeField>>();
  private @Nullable PreparedStatement getCompositeFieldDescStatement;
  private final ResourceLock lock = new ResourceLock();

  // basic pg types info:
  // 0 - type name
  // 1 - type oid
  // 2 - sql type
  // 3 - java class
  // 4 - array type oid
  private static final Object[][] types = {
      {"int2", Oid.INT2, Types.SMALLINT, "java.lang.Integer", Oid.INT2_ARRAY},
      {"int4", Oid.INT4, Types.INTEGER, "java.lang.Integer", Oid.INT4_ARRAY},
      {"oid", Oid.OID, Types.BIGINT, "java.lang.Long", Oid.OID_ARRAY},
      {"int8", Oid.INT8, Types.BIGINT, "java.lang.Long", Oid.INT8_ARRAY},
      {"money", Oid.MONEY, Types.DOUBLE, "java.lang.Double", Oid.MONEY_ARRAY},
      {"numeric", Oid.NUMERIC, Types.NUMERIC, "java.math.BigDecimal", Oid.NUMERIC_ARRAY},
      {"float4", Oid.FLOAT4, Types.REAL, "java.lang.Float", Oid.FLOAT4_ARRAY},
      {"float8", Oid.FLOAT8, Types.DOUBLE, "java.lang.Double", Oid.FLOAT8_ARRAY},
      {"char", Oid.CHAR, Types.CHAR, "java.lang.String", Oid.CHAR_ARRAY},
      {"bpchar", Oid.BPCHAR, Types.CHAR, "java.lang.String", Oid.BPCHAR_ARRAY},
      {"varchar", Oid.VARCHAR, Types.VARCHAR, "java.lang.String", Oid.VARCHAR_ARRAY},
      {"text", Oid.TEXT, Types.VARCHAR, "java.lang.String", Oid.TEXT_ARRAY},
      {"name", Oid.NAME, Types.VARCHAR, "java.lang.String", Oid.NAME_ARRAY},
      {"bytea", Oid.BYTEA, Types.BINARY, "[B", Oid.BYTEA_ARRAY},
      {"bool", Oid.BOOL, Types.BIT, "java.lang.Boolean", Oid.BOOL_ARRAY},
      {"bit", Oid.BIT, Types.BIT, "java.lang.Boolean", Oid.BIT_ARRAY},
      {"date", Oid.DATE, Types.DATE, "java.sql.Date", Oid.DATE_ARRAY},
      {"time", Oid.TIME, Types.TIME, "java.sql.Time", Oid.TIME_ARRAY},
      {"timetz", Oid.TIMETZ, Types.TIME, "java.sql.Time", Oid.TIMETZ_ARRAY},
      {"timestamp", Oid.TIMESTAMP, Types.TIMESTAMP, "java.sql.Timestamp", Oid.TIMESTAMP_ARRAY},
      {"timestamptz", Oid.TIMESTAMPTZ, Types.TIMESTAMP, "java.sql.Timestamp",
          Oid.TIMESTAMPTZ_ARRAY},
      {"refcursor", Oid.REF_CURSOR, Types.REF_CURSOR, "java.sql.ResultSet", Oid.REF_CURSOR_ARRAY},
      {"json", Oid.JSON, Types.OTHER, "com.aliyun.polardb2.util.PGobject", Oid.JSON_ARRAY},
      {"point", Oid.POINT, Types.OTHER, "com.aliyun.polardb2.geometric.PGpoint", Oid.POINT_ARRAY},
      {"box", Oid.BOX, Types.OTHER, "com.aliyun.polardb2.geometric.PGBox", Oid.BOX_ARRAY}
  };

  /**
   * PG maps several alias to real type names. When we do queries against pg_catalog, we must use
   * the real type, not an alias, so use this mapping.
   * <p>
   * Additional values used at runtime (including case variants) will be added to the map.
   * </p>
   */
  private static final ConcurrentMap<String, String> TYPE_ALIASES = new ConcurrentHashMap<>(20);

  static {
    TYPE_ALIASES.put("bool", "bool");
    TYPE_ALIASES.put("boolean", "bool");
    TYPE_ALIASES.put("smallint", "int2");
    TYPE_ALIASES.put("int2", "int2");
    TYPE_ALIASES.put("int", "int4");
    TYPE_ALIASES.put("integer", "int4");
    TYPE_ALIASES.put("int4", "int4");
    TYPE_ALIASES.put("long", "int8");
    TYPE_ALIASES.put("int8", "int8");
    TYPE_ALIASES.put("bigint", "int8");
    TYPE_ALIASES.put("float", "float8");
    TYPE_ALIASES.put("float4", "float4");
    TYPE_ALIASES.put("double", "float8");
    TYPE_ALIASES.put("float8", "float8");
    TYPE_ALIASES.put("decimal", "numeric");
    TYPE_ALIASES.put("numeric", "numeric");
  }

  @SuppressWarnings("method.invocation.invalid")
  public TypeInfoCache(BaseConnection conn, int unknownLength) {
    this.conn = conn;
    this.unknownLength = unknownLength;
    oidToPgName = new HashMap<Integer, String>((int) Math.round(types.length * 1.5));
    pgNameToOid = new HashMap<String, Integer>((int) Math.round(types.length * 1.5));
    javaArrayTypeToOid = new HashMap<String, Integer>((int) Math.round(types.length * 1.5));
    pgNameToJavaClass = new HashMap<String, String>((int) Math.round(types.length * 1.5));
    pgNameToPgObject = new HashMap<String, Class<? extends PGobject>>((int) Math.round(types.length * 1.5));
    pgArrayToPgType = new HashMap<Integer, Integer>((int) Math.round(types.length * 1.5));
    arrayOidToDelimiter = new HashMap<Integer, Character>((int) Math.round(types.length * 2.5));
    oidToTypCategory = new HashMap<Integer, Character>((int) Math.round(types.length * 1.5));

    // needs to be synchronized because the iterator is returned
    // from getPGTypeNamesWithSQLTypes()
    pgNameToSQLType = Collections.synchronizedMap(new HashMap<String, Integer>((int) Math.round(types.length * 1.5)));
    oidToSQLType = Collections.synchronizedMap(new HashMap<Integer, Integer>((int) Math.round(types.length * 1.5)));
    sqlTypeToOid = Collections.synchronizedMap(new HashMap<Integer, Integer>((int) Math.round(types.length * 1.5)));

    for (Object[] type : types) {
      String pgTypeName = (String) type[0];
      Integer oid = (Integer) type[1];
      Integer sqlType = (Integer) type[2];
      String javaClass = (String) type[3];
      Integer arrayOid = (Integer) type[4];

      addCoreType(pgTypeName, oid, sqlType, javaClass, arrayOid);
    }

    pgNameToJavaClass.put("hstore", Map.class.getName());
  }

  public void addCoreType(String pgTypeName, Integer oid, Integer sqlType,
      String javaClass, Integer arrayOid) {
    try (ResourceLock ignore = lock.obtain()) {
      pgNameToJavaClass.put(pgTypeName, javaClass);
      pgNameToOid.put(pgTypeName, oid);
      oidToPgName.put(oid, pgTypeName);
      javaArrayTypeToOid.put(javaClass, arrayOid);
      pgArrayToPgType.put(arrayOid, oid);
      pgNameToSQLType.put(pgTypeName, sqlType);
      oidToSQLType.put(oid, sqlType);
      sqlTypeToOid.put(sqlType, oid);

      // Currently we hardcode all core types array delimiter
      // to a comma. In a stock install the only exception is
      // the box datatype and it's not a JDBC core type.
      //
      Character delim = ',';
      if (pgTypeName.equals("box")) {
        delim = ';';
      }
      arrayOidToDelimiter.put(oid, delim);
      arrayOidToDelimiter.put(arrayOid, delim);

      String pgArrayTypeName = pgTypeName + "[]";
      pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
      pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
      oidToSQLType.put(arrayOid, Types.ARRAY);
      pgNameToOid.put(pgArrayTypeName, arrayOid);
      pgArrayTypeName = "_" + pgTypeName;
      if (!pgNameToJavaClass.containsKey(pgArrayTypeName)) {
        pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
        pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
        pgNameToOid.put(pgArrayTypeName, arrayOid);
        oidToPgName.put(arrayOid, pgArrayTypeName);
      }
    }
  }

  public void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      pgNameToPgObject.put(type, klass);
      pgNameToJavaClass.put(type, klass.getName());
    }
  }

  public Iterator<String> getPGTypeNamesWithSQLTypes() {
    return pgNameToSQLType.keySet().iterator();
  }

  public Iterator<Integer> getPGTypeOidsWithSQLTypes() {
    return oidToSQLType.keySet().iterator();
  }

  private String getSQLTypeQuery(boolean typoidParam) {
    // There's no great way of telling what's an array type.
    // People can name their own types starting with _.
    // Other types use typelem that aren't actually arrays, like box.
    //
    // in case of multiple records (in different schemas) choose the one from the current
    // schema,
    // otherwise take the last version of a type that is at least more deterministic then before
    // (keeping old behaviour of finding types, that should not be found without correct search
    // path)
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT typinput='pg_catalog.array_in'::regproc as is_array, typtype, typname, pg_type.oid ");
    sql.append("  FROM pg_catalog.pg_type ");
    sql.append("  LEFT JOIN (select ns.oid as nspoid, ns.nspname, r.r ");
    sql.append("          from pg_namespace as ns ");
    // -- go with older way of unnesting array to be compatible with 8.0
    sql.append("          join ( select s.r, (current_schemas(false))[s.r] as nspname ");
    sql.append("                   from generate_series(1, array_upper(current_schemas(false), 1)) as s(r) ) as r ");
    sql.append("         using ( nspname ) ");
    sql.append("       ) as sp ");
    sql.append("    ON sp.nspoid = typnamespace ");
    if (typoidParam) {
      sql.append(" WHERE pg_type.oid = ? ");
    }
    sql.append(" ORDER BY sp.r, pg_type.oid DESC;");
    return sql.toString();
  }

  private int getSQLTypeFromQueryResult(ResultSet rs) throws SQLException {
    Integer type = null;
    boolean isArray = rs.getBoolean("is_array");
    String typtype = rs.getString("typtype");
    if (isArray) {
      type = Types.ARRAY;
    } else if ("c".equals(typtype)) {
      type = Types.STRUCT;
    } else if ("d".equals(typtype)) {
      type = Types.DISTINCT;
    } else if ("e".equals(typtype)) {
      type = Types.VARCHAR;
    }
    /* POLAR: typtype='a' (PolarDB TABLE OF / VARRAY / INDEX BY collection) was
     * previously force-mapped to Types.ARRAY here. That regressed user code
     * which still relied on the legacy behaviour of registerOutParameter(idx,
     * Types.OTHER) returning a raw PGobject (HSTORE-style text). We now leave
     * such collection types at Types.OTHER and let downstream call sites
     * (PgCallableStatement.convertOutParamValue, PgResultSet.getObject) wrap
     * the value into PgArray / PgCompositeObject only when the user explicitly
     * registers ARRAY / STRUCT. See isOracleTableOfType(int) for the
     * server-side category check used by those wrappers. */
    if (type == null) {
      type = Types.OTHER;
    }
    return type;
  }

  /**
   * POLAR: Returns {@code true} if the given OID names a PolarDB Oracle-style
   * collection type whose textual representation is NOT the standard
   * {@code {elem1,elem2,...}} array literal. This includes:
   * <ul>
   *   <li>{@code 'J'} - VARRAY</li>
   *   <li>{@code 'K'} - Nested Table / TABLE OF</li>
   *   <li>{@code 'L'} - Associative Array / INDEX BY (HSTORE-style)</li>
   * </ul>
   *
   * <p>Used by {@code PgCallableStatement.convertOutParamValue} to decide
   * whether a {@code Types.OTHER} value coming back from the server should be
   * wrapped as {@link PgArray} when the caller registered
   * {@link java.sql.Types#ARRAY}.
   */
  public boolean isOracleTableOfType(int oid) throws SQLException {
    char cat = getTypeCategory(oid);
    return cat == 'J' || cat == 'K' || cat == 'L';
  }

  private PreparedStatement prepareGetAllTypeInfoStatement() throws SQLException {
    PreparedStatement getAllTypeInfoStatement = this.getAllTypeInfoStatement;
    if (getAllTypeInfoStatement == null) {
      getAllTypeInfoStatement = conn.prepareStatement(getSQLTypeQuery(false));
      this.getAllTypeInfoStatement = getAllTypeInfoStatement;
    }
    return getAllTypeInfoStatement;
  }

  public void cacheSQLTypes() throws SQLException {
    LOGGER.log(Level.FINEST, "caching all SQL typecodes");
    PreparedStatement getAllTypeInfoStatement = prepareGetAllTypeInfoStatement();
    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getAllTypeInfoStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }
    ResultSet rs = castNonNull(getAllTypeInfoStatement.getResultSet());
    while (rs.next()) {
      String typeName = castNonNull(rs.getString("typname"));
      Integer type = getSQLTypeFromQueryResult(rs);
      if (!pgNameToSQLType.containsKey(typeName)) {
        pgNameToSQLType.put(typeName, type);
      }

      Integer typeOid = longOidToInt(castNonNull(rs.getLong("oid")));
      if (!oidToSQLType.containsKey(typeOid)) {
        oidToSQLType.put(typeOid, type);
      }
    }
    rs.close();
  }

  private PreparedStatement prepareGetTypeInfoStatement() throws SQLException {
    PreparedStatement getTypeInfoStatement = this.getTypeInfoStatement;
    if (getTypeInfoStatement == null) {
      getTypeInfoStatement = conn.prepareStatement(getSQLTypeQuery(true));
      this.getTypeInfoStatement = getTypeInfoStatement;
    }
    return getTypeInfoStatement;
  }

  public int getSQLType(String pgTypeName) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      /*
        Get a few things out of the way such as arrays and known types
      */
      if (pgTypeName.endsWith("[]")) {
        return Types.ARRAY;
      }
      Integer i = this.pgNameToSQLType.get(pgTypeName);
      if (i != null) {
        return i;
      }

      /*
        All else fails then we will query the database.
        save for future calls
      */
      i = getSQLType(castNonNull(getPGType(pgTypeName)));

      pgNameToSQLType.put(pgTypeName, i);
      return i;
    }
  }

  @Override
  public int getJavaArrayType(String className) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      Integer oid = javaArrayTypeToOid.get(className);
      if (oid == null) {
        return Oid.UNSPECIFIED;
      }
      return oid;
    }
  }

  public int getSQLType(int typeOid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (typeOid == Oid.UNSPECIFIED) {
        return Types.OTHER;
      }

      Integer i = oidToSQLType.get(typeOid);
      if (i != null) {
        return i;
      }

      LOGGER.log(Level.FINEST, "querying SQL typecode for pg type oid '{0}'", intOidToLong(typeOid));

      PreparedStatement getTypeInfoStatement = prepareGetTypeInfoStatement();

      getTypeInfoStatement.setLong(1, intOidToLong(typeOid));

      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) getTypeInfoStatement)
          .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      ResultSet rs = castNonNull(getTypeInfoStatement.getResultSet());

      int sqlType = Types.OTHER;
      if (rs.next()) {
        sqlType = getSQLTypeFromQueryResult(rs);
      }
      rs.close();

      oidToSQLType.put(typeOid, sqlType);
      return sqlType;
    }
  }

  /**
   * POLAR: Checks once per connection whether {@code pg_catalog.pg_namespace}
   * exposes {@code nsppkgns} (PolarDB embedded package namespaces). Querying
   * {@code pg_attribute} avoids SQL errors inside an active transaction when
   * connected to standard PostgreSQL servers.
   */
  private boolean hasPackageNamespaceSupport() throws SQLException {
    if (packageNamespaceSupported != null) {
      return packageNamespaceSupported;
    }
    if (checkPackageNamespaceStatement == null) {
      checkPackageNamespaceStatement = conn.prepareStatement(
          "SELECT 1 FROM pg_catalog.pg_attribute "
          + "WHERE attrelid = 'pg_catalog.pg_namespace'::regclass "
          + "AND attname = 'nsppkgns' AND NOT attisdropped LIMIT 1");
    }
    boolean supported = false;
    if (((BaseStatement) checkPackageNamespaceStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      ResultSet rs = castNonNull(checkPackageNamespaceStatement.getResultSet());
      try {
        supported = rs.next();
      } finally {
        rs.close();
      }
    }
    packageNamespaceSupported = supported;
    return supported;
  }

  /**
   * POLAR: Splits a possibly qualified type name into 1..3 normalized
   * identifier parts while respecting double-quoted identifiers (e.g.
   * {@code "Evil.Table"}, {@code CAS.PKG_CRR.TBL_CRR_CALLBACK_RESULT},
   * {@code "CAS"."PKG_CRR"."TBL_CRR_CALLBACK_RESULT"}). Unquoted parts are
   * folded to lower case; quoted parts preserve exact case and unescape
   * {@code ""}. Returns {@code null} for malformed or >3-part names.
   */
  static String @Nullable [] parseQualifiedTypeName(String fullName) {
    if (fullName.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<String>(3);
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    boolean wasQuoted = false;
    for (int i = 0; i < fullName.length(); i++) {
      char c = fullName.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < fullName.length() && fullName.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
      } else if (c == '"') {
        if (current.length() > 0) {
          return null;
        }
        inQuotes = true;
        wasQuoted = true;
      } else if (c == '.') {
        if (!wasQuoted && current.length() == 0) {
          return null;
        }
        parts.add(wasQuoted ? current.toString() : current.toString().toLowerCase(Locale.ROOT));
        if (parts.size() >= 3) {
          return null;
        }
        current.setLength(0);
        wasQuoted = false;
      } else {
        if (wasQuoted) {
          return null;
        }
        current.append(c);
      }
    }
    if (inQuotes || (!wasQuoted && current.length() == 0)) {
      return null;
    }
    parts.add(wasQuoted ? current.toString() : current.toString().toLowerCase(Locale.ROOT));
    return parts.toArray(new String[0]);
  }

  private static String quoteIdent(String ident) {
    return "\"" + ident.replace("\"", "\"\"") + "\"";
  }

  private static String formatPackageQualifiedName(
      String pkgSchema, String schema, String name) {
    return quoteIdent(pkgSchema) + "." + quoteIdent(schema) + "." + quoteIdent(name);
  }

  private PreparedStatement prepareNoMatchStatement() throws SQLException {
    if (getOidStatementNoMatch == null) {
      getOidStatementNoMatch = conn.prepareStatement(
          "SELECT NULL::oid, NULL::name WHERE false");
    }
    return getOidStatementNoMatch;
  }

  private PreparedStatement prepareComplexOidStatement(boolean isArray) throws SQLException {
    if (isArray) {
      if (getOidStatementComplexArray == null) {
        String sql;
        if (conn.haveMinimumServerVersion(ServerVersion.v8_3)) {
          sql = "SELECT t.typarray, arr.typname "
              + "  FROM pg_catalog.pg_type t"
              + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
              + "  JOIN pg_catalog.pg_type arr ON arr.oid = t.typarray"
              + " WHERE t.typname = ? AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
              + " ORDER BY t.oid DESC LIMIT 1";
        } else {
          sql = "SELECT t.oid, t.typname "
              + "  FROM pg_catalog.pg_type t"
              + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
              + " WHERE t.typelem = (SELECT oid FROM pg_catalog.pg_type WHERE typname = ?)"
              + " AND substring(t.typname, 1, 1) = '_' AND t.typlen = -1"
              + " AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
              + " ORDER BY t.typelem DESC LIMIT 1";
        }
        getOidStatementComplexArray = conn.prepareStatement(sql);
      }
      return getOidStatementComplexArray;
    }
    if (getOidStatementComplexNonArray == null) {
      String sql = "SELECT t.oid, t.typname "
          + "  FROM pg_catalog.pg_type t"
          + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
          + " WHERE t.typname = ? AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
          + " ORDER BY t.oid DESC LIMIT 1";
      getOidStatementComplexNonArray = conn.prepareStatement(sql);
    }
    return getOidStatementComplexNonArray;
  }

  private PreparedStatement prepareDottedOidStatement(boolean isArray) throws SQLException {
    String searchPathJoin =
        " LEFT JOIN ("
        + "   SELECT ns.oid AS nspoid, r.r"
        + "     FROM pg_catalog.pg_namespace ns"
        + "     JOIN ("
        + "       SELECT s.r, (pg_catalog.current_schemas(false))[s.r] AS nspname"
        + "         FROM pg_catalog.generate_series(1, pg_catalog.array_upper(pg_catalog.current_schemas(false), 1)) AS s(r)"
        + "     ) AS r ON ns.nspname = r.nspname AND COALESCE(ns.nsppkgns, 0) = 0"
        + " ) AS sp ON sp.nspoid = p.oid";
    String orderBy =
        " ORDER BY CASE WHEN COALESCE(n.nsppkgns, 0) = 0 THEN 0"
        + "              WHEN sp.r IS NOT NULL THEN 1"
        + "              ELSE 2 END,"
        + "          sp.r,"
        + "          t.oid DESC"
        + " LIMIT 1";
    if (isArray) {
      if (getOidStatementDottedArray == null) {
        String sql = "SELECT t.typarray, "
            + "CASE WHEN p.nspname IS NOT NULL THEN "
            + "'\"' || replace(p.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(n.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(arr.typname, '\"', '\"\"') || '\"' "
            + "ELSE arr.typname END"
            + "  FROM pg_catalog.pg_type t"
            + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
            + "  JOIN pg_catalog.pg_type arr ON arr.oid = t.typarray"
            + "  LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid"
            + searchPathJoin
            + " WHERE t.typname = ? AND n.nspname = ?"
            + orderBy;
        getOidStatementDottedArray = conn.prepareStatement(sql);
      }
      return getOidStatementDottedArray;
    }
    if (getOidStatementDottedNonArray == null) {
      String sql = "SELECT t.oid, "
          + "CASE WHEN p.nspname IS NOT NULL THEN "
          + "'\"' || replace(p.nspname, '\"', '\"\"') || '\".\"' "
          + "|| replace(n.nspname, '\"', '\"\"') || '\".\"' "
          + "|| replace(t.typname, '\"', '\"\"') || '\"' "
          + "ELSE t.typname END"
          + "  FROM pg_catalog.pg_type t"
          + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
          + "  LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid"
          + searchPathJoin
          + " WHERE t.typname = ? AND n.nspname = ?"
          + orderBy;
      getOidStatementDottedNonArray = conn.prepareStatement(sql);
    }
    return getOidStatementDottedNonArray;
  }

  private PreparedStatement preparePackageOidStatement(boolean isArray) throws SQLException {
    if (isArray) {
      if (getOidStatementPkgArray == null) {
        String sql = "SELECT t.typarray, "
            + "'\"' || replace(p.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(n.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(arr.typname, '\"', '\"\"') || '\"'"
            + "  FROM pg_catalog.pg_type t"
            + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
            + "  JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid"
            + "  JOIN pg_catalog.pg_type arr ON arr.oid = t.typarray"
            + " WHERE t.typname = ? AND n.nspname = ? AND p.nspname = ?"
            + " ORDER BY t.oid DESC LIMIT 1";
        getOidStatementPkgArray = conn.prepareStatement(sql);
      }
      return getOidStatementPkgArray;
    }
    if (getOidStatementPkgNonArray == null) {
      String sql = "SELECT t.oid, "
          + "'\"' || replace(p.nspname, '\"', '\"\"') || '\".\"' "
          + "|| replace(n.nspname, '\"', '\"\"') || '\".\"' "
          + "|| replace(t.typname, '\"', '\"\"') || '\"'"
          + "  FROM pg_catalog.pg_type t"
          + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
          + "  JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid"
          + " WHERE t.typname = ? AND n.nspname = ? AND p.nspname = ?"
          + " ORDER BY t.oid DESC LIMIT 1";
      getOidStatementPkgNonArray = conn.prepareStatement(sql);
    }
    return getOidStatementPkgNonArray;
  }

  private PreparedStatement getOidStatement(String pgTypeName) throws SQLException {
    boolean isArray = pgTypeName.endsWith("[]");
    boolean hasQuote = pgTypeName.contains("\"");
    int dotIndex = pgTypeName.indexOf('.');

    if (dotIndex == -1 && !hasQuote && !isArray) {
      if (getOidStatementSimple == null) {
        String sql;
        // see comments in @getSQLType()
        // -- go with older way of unnesting array to be compatible with 8.0
        sql = "SELECT pg_type.oid, typname "
              + "  FROM pg_catalog.pg_type "
              + "  LEFT "
              + "  JOIN (select ns.oid as nspoid, ns.nspname, r.r "
              + "          from pg_namespace as ns "
              + "          join ( select s.r, (current_schemas(false))[s.r] as nspname "
              + "                   from generate_series(1, array_upper(current_schemas(false), 1)) as s(r) ) as r "
              + "         using ( nspname ) "
              + "       ) as sp "
              + "    ON sp.nspoid = typnamespace "
              + " WHERE typname = ? "
              + " ORDER BY sp.r, pg_type.oid DESC LIMIT 1;";
        getOidStatementSimple = conn.prepareStatement(sql);
      }
      // coerce to lower case to handle upper case type names
      String lcName = pgTypeName.toLowerCase(Locale.ROOT);
      // default arrays are represented with _ as prefix ... this dont even work for public schema
      // fully
      getOidStatementSimple.setString(1, lcName);
      return getOidStatementSimple;
    }

    String fullName = isArray ? pgTypeName.substring(0, pgTypeName.length() - 2) : pgTypeName;
    String @Nullable [] parts = parseQualifiedTypeName(fullName);
    if (parts == null) {
      return prepareNoMatchStatement();
    }

    if (parts.length == 3) {
      if (!hasPackageNamespaceSupport()
          || (isArray && !conn.haveMinimumServerVersion(ServerVersion.v8_3))) {
        return prepareNoMatchStatement();
      }
      PreparedStatement pkgStmt = preparePackageOidStatement(isArray);
      pkgStmt.setString(1, parts[2]);
      pkgStmt.setString(2, parts[1]);
      pkgStmt.setString(3, parts[0]);
      return pkgStmt;
    }

    if (parts.length == 2 && hasPackageNamespaceSupport()
        && (!isArray || conn.haveMinimumServerVersion(ServerVersion.v8_3))) {
      PreparedStatement dottedStmt = prepareDottedOidStatement(isArray);
      dottedStmt.setString(1, parts[1]);
      dottedStmt.setString(2, parts[0]);
      return dottedStmt;
    }

    PreparedStatement oidStatementComplex = prepareComplexOidStatement(isArray);
    String schema = parts.length == 2 ? parts[0] : null;
    String name = parts.length == 2 ? parts[1] : parts[0];
    oidStatementComplex.setString(1, name);
    oidStatementComplex.setString(2, schema);
    oidStatementComplex.setBoolean(3, schema == null);
    return oidStatementComplex;
  }

  public int getPGType(String pgTypeName) throws SQLException {
    return getPGType(pgTypeName, 0);
  }

  private int getPGType(String pgTypeName, int synonymDepth) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      // there really isn't anything else to return other than UNSPECIFIED here.
      if (pgTypeName == null) {
        return Oid.UNSPECIFIED;
      }

      Integer oid = pgNameToOid.get(pgTypeName);
      if (oid != null) {
        return oid;
      }

      PreparedStatement oidStatement = getOidStatement(pgTypeName);

      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) oidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      oid = Oid.UNSPECIFIED;
      ResultSet rs = castNonNull(oidStatement.getResultSet());
      if (rs.next()) {
        oid = (int) rs.getLong(1);
        String internalName = castNonNull(rs.getString(2));
        oidToPgName.put(oid, internalName);
        pgNameToOid.put(internalName, oid);
      }
      rs.close();

      // POLAR: Synonym fallback — if pg_type lookup returned nothing, try resolving
      // the name through Oracle-compatible synonyms (all_synonyms view).
      // Supports standalone synonyms (syn_name), schema-qualified synonyms
      // (schema.syn_name), and package-level synonyms (pkg_syn.type_name /
      // schema.pkg_syn.type_name).
      if (oid == Oid.UNSPECIFIED && synonymDepth < MAX_SYNONYM_DEPTH) {
        String resolvedName = resolveSynonym(pgTypeName);
        if (resolvedName != null && !resolvedName.equals(pgTypeName)) {
          // Recurse with the resolved real type name
          oid = getPGType(resolvedName, synonymDepth + 1);
        }
      }

      pgNameToOid.put(pgTypeName, oid);

      return oid;
    }
  }

  private boolean hasAllSynonymsSupport() throws SQLException {
    if (!synonymLookupSupported) {
      return false;
    }
    if (allSynonymsPresent) {
      return true;
    }
    if (checkAllSynonymsStatement == null) {
      checkAllSynonymsStatement = conn.prepareStatement(
          "SELECT 1 FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'sys'"
          + " AND c.relname = 'all_synonyms'"
          + " AND c.relkind IN ('r', 'v', 'm', 'p')"
          + " AND pg_catalog.has_table_privilege(c.oid, 'SELECT')"
          + " AND 4 = (SELECT COUNT(*) FROM pg_catalog.pg_attribute a"
          + "   WHERE a.attrelid = c.oid"
          + "   AND a.attname IN ('synonym_name', 'synonym_schema', 'table_schema', 'table_name')"
          + "   AND NOT a.attisdropped)"
          + " LIMIT 1");
    }
    boolean present = false;
    if (((BaseStatement) checkAllSynonymsStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      ResultSet rs = castNonNull(checkAllSynonymsStatement.getResultSet());
      try {
        present = rs.next();
      } finally {
        rs.close();
      }
    }
    if (present) {
      allSynonymsPresent = true;
    }
    return present;
  }

  /**
   * POLAR: Resolve a synonym name (or synonym-qualified package type name)
   * to the real type name it points to using {@code sys.all_synonyms}.
   *
   * @param synonymName the type or synonym name to resolve
   * @return the real type name, or null if not a synonym / view unavailable
   */
  private @Nullable String resolveSynonym(String synonymName) throws SQLException {
    if (!hasAllSynonymsSupport()) {
      return null;
    }
    boolean isArray = synonymName.endsWith("[]");
    String baseName = isArray ? synonymName.substring(0, synonymName.length() - 2) : synonymName;
    String @Nullable [] parts = parseQualifiedTypeName(baseName);
    if (parts == null) {
      return null;
    }
    String resolved = null;
    if (parts.length == 1) {
      resolved = lookupSynonym(null, parts[0].toLowerCase(Locale.ROOT));
    } else if (parts.length == 2) {
      // First try schema.synonym; if not found, treat part[0] as a synonym for a package.
      resolved = lookupSynonym(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
      if (resolved == null) {
        String pkgTarget = lookupSynonym(null, parts[0].toLowerCase(Locale.ROOT));
        if (pkgTarget != null) {
          resolved = pkgTarget + "." + quoteIdent(parts[1]);
        }
      }
    } else if (parts.length == 3) {
      String pkgTarget = lookupSynonym(
          parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
      if (pkgTarget != null) {
        resolved = pkgTarget + "." + quoteIdent(parts[2]);
      }
    }
    if (resolved != null && isArray) {
      return resolved + "[]";
    }
    return resolved;
  }

  private @Nullable String lookupSynonym(
      @Nullable String synSchema, String synName) throws SQLException {
    try {
      PreparedStatement stmt;
      if (synSchema == null) {
        if (resolveSynonymStatement == null) {
          String sql = "SELECT LOWER(table_schema), LOWER(table_name) FROM sys.all_synonyms"
              + " WHERE LOWER(synonym_name) = ?"
              + " ORDER BY CASE WHEN LOWER(synonym_schema) = ANY(pg_catalog.current_schemas(false))"
              + "               THEN 0"
              + "               WHEN UPPER(synonym_schema) = 'PUBLIC' THEN 1"
              + "               ELSE 2 END"
              + " LIMIT 1";
          resolveSynonymStatement = conn.prepareStatement(sql);
        }
        resolveSynonymStatement.setString(1, synName);
        stmt = resolveSynonymStatement;
      } else {
        if (resolveSynonymQualifiedStatement == null) {
          String sql = "SELECT LOWER(table_schema), LOWER(table_name) FROM sys.all_synonyms"
              + " WHERE LOWER(synonym_schema) = ? AND LOWER(synonym_name) = ? LIMIT 1";
          resolveSynonymQualifiedStatement = conn.prepareStatement(sql);
        }
        resolveSynonymQualifiedStatement.setString(1, synSchema);
        resolveSynonymQualifiedStatement.setString(2, synName);
        stmt = resolveSynonymQualifiedStatement;
      }
      if (!((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        return null;
      }
      ResultSet rs = castNonNull(stmt.getResultSet());
      String resolved = null;
      try {
        if (rs.next()) {
          String tableSchema = rs.getString(1);
          String tableName = rs.getString(2);
          if (tableName != null && !tableName.isEmpty()) {
            resolved = (tableSchema != null && !tableSchema.isEmpty())
                ? quoteIdent(tableSchema) + "." + quoteIdent(tableName)
                : quoteIdent(tableName);
          }
        }
      } finally {
        rs.close();
      }
      return resolved;
    } catch (SQLException e) {
      String sqlState = e.getSQLState();
      if ("42P01".equals(sqlState) || "42703".equals(sqlState) || "42883".equals(sqlState)) {
        // Missing sys.all_synonyms, expected column, or helper function — disable permanently
        synonymLookupSupported = false;
        LOGGER.log(Level.WARNING,
            "Disabling Oracle synonym lookup for this connection (SQLState={0}): {1}",
            new Object[]{sqlState, e.getMessage()});
      } else {
        LOGGER.log(Level.FINE,
            "Ignoring transient error during Oracle synonym lookup (SQLState={0}): {1}",
            new Object[]{sqlState, e.getMessage()});
      }
      return null;
    }
  }

  /**
   * POLAR: Return the field type OIDs of a composite type, ordered by attnum.
   * Used to restore each field of a composite (record) literal to its proper
   * Java type (NUMBER -&gt; BigDecimal, DATE/TIMESTAMP -&gt; Timestamp, etc.),
   * matching Oracle ojdbc behavior for Struct.getAttributes().
   *
   * @param compositeOid the OID of the composite type
   * @return array of field type OIDs ordered by attnum, or {@code null} if the
   *         type is not composite or has no attributes
   */
  public int @Nullable [] getCompositeFieldTypeOids(int compositeOid) throws SQLException {
    if (compositeOid == Oid.UNSPECIFIED) {
      return null;
    }
    int @Nullable [] cached = oidToCompositeFieldOids.get(compositeOid);
    if (cached != null) {
      return cached.length == 0 ? null : cached;
    }
    try (ResourceLock ignore = lock.obtain()) {
      if (getCompositeFieldOidsStatement == null) {
        // POLAR: exclude PolarDB's built-in hidden ROWID column (positive attnum, not dropped):
        // the server never includes it in the composite wire format, and a record literal
        // with a value for it is rejected ("malformed record literal: Too many columns").
        String sql = "SELECT a.atttypid FROM pg_catalog.pg_attribute a"
            + " JOIN pg_catalog.pg_type t ON t.typrelid = a.attrelid"
            + " WHERE t.oid = ? AND a.attnum > 0 AND NOT a.attisdropped"
            + " AND a.attname <> 'polar_sys_rowid_attr'"
            + " ORDER BY a.attnum";
        getCompositeFieldOidsStatement = conn.prepareStatement(sql);
      }
      getCompositeFieldOidsStatement.setInt(1, compositeOid);
      if (!((BaseStatement) getCompositeFieldOidsStatement)
          .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }
      ResultSet rs = castNonNull(getCompositeFieldOidsStatement.getResultSet());
      java.util.List<Integer> oids = new java.util.ArrayList<Integer>();
      while (rs.next()) {
        oids.add((int) rs.getLong(1));
      }
      rs.close();

      int[] result = new int[oids.size()];
      for (int i = 0; i < result.length; i++) {
        result[i] = oids.get(i);
      }
      // Cache even empty result (as zero-length) to avoid repeated queries.
      oidToCompositeFieldOids.put(compositeOid, result);
      return result.length == 0 ? null : result;
    }
  }

  /**
   * POLAR: Return the field descriptions (attname, atttypid, attnum) of a composite type,
   * ordered by attnum. Results are cached per composite type OID. This backs the public
   * API {@code PGConnection.getCompositeTypeFields(String)} which lets application
   * frameworks map Java bean fields to composite-type fields by name.
   */
  @Override
  public @Nullable List<PGCompositeField> getCompositeFieldDescriptions(int compositeOid)
      throws SQLException {
    if (compositeOid == Oid.UNSPECIFIED) {
      return null;
    }
    List<PGCompositeField> cached = oidToCompositeFields.get(compositeOid);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    try (ResourceLock ignore = lock.obtain()) {
      if (getCompositeFieldDescStatement == null) {
        // POLAR: exclude PolarDB's built-in hidden ROWID column the same way as
        // getCompositeFieldTypeOids above, so frameworks driven by
        // getCompositeTypeFields see exactly the user columns the server serializes.
        String sql = "SELECT a.attname, a.atttypid, a.attnum FROM pg_catalog.pg_attribute a"
            + " JOIN pg_catalog.pg_type t ON t.typrelid = a.attrelid"
            + " WHERE t.oid = ? AND a.attnum > 0 AND NOT a.attisdropped"
            + " AND a.attname <> 'polar_sys_rowid_attr'"
            + " ORDER BY a.attnum";
        getCompositeFieldDescStatement = conn.prepareStatement(sql);
      }
      getCompositeFieldDescStatement.setInt(1, compositeOid);
      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) getCompositeFieldDescStatement)
          .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }
      ResultSet rs = castNonNull(getCompositeFieldDescStatement.getResultSet());
      List<PGCompositeField> fields = new ArrayList<PGCompositeField>();
      while (rs.next()) {
        fields.add(new PGCompositeField(
            castNonNull(rs.getString(1), "attname"),
            (int) rs.getLong(3),
            (int) rs.getLong(2)));
      }
      rs.close();
      // Cache even empty result to avoid repeated queries.
      oidToCompositeFields.put(compositeOid, fields);
      return fields.isEmpty() ? null : fields;
    }
  }

  private static boolean isSimpleLowerIdent(String ident) {
    return ident.equals(ident.toLowerCase(Locale.ROOT)) && ident.indexOf('.') == -1;
  }

  private String cachePackageTypeName(int typeOid, String pkgSchema, String schema, String name) {
    String fullName = formatPackageQualifiedName(pkgSchema, schema, name);
    if (isSimpleLowerIdent(pkgSchema) && isSimpleLowerIdent(schema) && isSimpleLowerIdent(name)) {
      pgNameToOid.put(pkgSchema + "." + schema + "." + name, typeOid);
    }
    pgNameToOid.put(fullName, typeOid);
    oidToPgName.put(typeOid, fullName);
    return fullName;
  }

  public @Nullable String getPGType(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (oid == Oid.UNSPECIFIED) {
        // TODO: it would be great to forbid UNSPECIFIED argument, and make the return type non-nullable
        return null;
      }

      String pgTypeName = oidToPgName.get(oid);
      if (pgTypeName != null) {
        return pgTypeName;
      }

      boolean hasPkgSupport = hasPackageNamespaceSupport();
      PreparedStatement getNameStatement = prepareGetNameStatement(hasPkgSupport);

      getNameStatement.setInt(1, oid);

      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) getNameStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      ResultSet rs = castNonNull(getNameStatement.getResultSet());
      if (rs.next()) {
        boolean onPath = rs.getBoolean(1);
        String schema = castNonNull(rs.getString(2), "schema");
        String name = castNonNull(rs.getString(3), "name");
        String pkgSchema = hasPkgSupport ? rs.getString(4) : null;
        if (pkgSchema != null) {
          pgTypeName = cachePackageTypeName(oid, pkgSchema, schema, name);
        } else {
          if (onPath) {
            pgTypeName = name;
            pgNameToOid.put(schema + "." + name, oid);
          } else {
            // TODO: escaping !?
            pgTypeName = "\"" + schema + "\".\"" + name + "\"";
            // if all is lowercase add special type info
            // TODO: should probably check for all special chars
            if (isSimpleLowerIdent(schema) && isSimpleLowerIdent(name)) {
              pgNameToOid.put(schema + "." + name, oid);
            }
          }
          pgNameToOid.put(pgTypeName, oid);
          oidToPgName.put(oid, pgTypeName);
        }
      }
      rs.close();

      return pgTypeName;
    }
  }

  private PreparedStatement prepareGetNameStatement(boolean hasPkgSupport) throws SQLException {
    PreparedStatement getNameStatement = this.getNameStatement;
    if (getNameStatement == null) {
      String sql;
      if (hasPkgSupport) {
        sql = "SELECT n.nspname = ANY(current_schemas(true)), n.nspname, t.typname, p.nspname "
            + "FROM pg_catalog.pg_type t "
            + "JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid "
            + "LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid "
            + "WHERE t.oid = ?";
      } else {
        sql = "SELECT n.nspname = ANY(current_schemas(true)), n.nspname, t.typname "
            + "FROM pg_catalog.pg_type t "
            + "JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid WHERE t.oid = ?";
      }

      this.getNameStatement = getNameStatement = conn.prepareStatement(sql);
    }
    return getNameStatement;
  }

  public int getPGArrayType(@Nullable String elementTypeName) throws SQLException {
    elementTypeName = getTypeForAlias(elementTypeName);
    if (elementTypeName == null) {
      return Oid.UNSPECIFIED;
    }

    int oid = resolveArrayType(elementTypeName);
    if (oid == Oid.UNSPECIFIED && elementTypeName.contains("\"")) {
      // POLAR: Support Oracle-style quoted uppercase names (e.g. "\"CAS\".\"PKG\".\"TBL\"")
      // only after exact-case quoted lookup finds nothing.
      String lcName = elementTypeName.toLowerCase(Locale.ROOT);
      if (!lcName.equals(elementTypeName)) {
        oid = resolveArrayType(lcName);
      }
    }
    return oid;
  }

  private int resolveArrayType(String elementTypeName) throws SQLException {
    // POLAR: First try to find a PolarDB collection type for this element type.
    // PolarDB supports VARRAY ('J'), nested table/TABLE OF ('K'), and associative
    // array/INDEX BY ('L'). These Oracle-compatible collection types should be
    // preferred when they exist, as Oracle mode stored procedures use them.
    int elemOid = getPGType(elementTypeName);
    if (elemOid != Oid.UNSPECIFIED) {
      int collOid = findCollectionTypeByElement(elemOid);
      if (collOid != Oid.UNSPECIFIED) {
        return collOid;
      }
    }

    // Fall back to standard PostgreSQL array notation (elementType[])
    int oid = getPGType(elementTypeName + "[]");
    // If not found, try the type name directly (for VARRAY types like actor_name_array)
    if (oid == Oid.UNSPECIFIED) {
      oid = getPGType(elementTypeName);
    }
    return oid;
  }

  /**
   * POLAR: Find a collection type whose element type matches the given OID.
   * PolarDB supports three kinds of Oracle-compatible collection types:
   * <ul>
   *   <li>VARRAY (typcategory='J') - variable-length arrays</li>
   *   <li>Nested Table / TABLE OF (typcategory='K') - unbounded collections</li>
   *   <li>Associative Array / INDEX BY (typcategory='L') - key-value maps</li>
   * </ul>
   *
   * <p>This lookup is restricted to element types that are composite/record types
   * (typcategory='C'). For standard scalar types (int4, varchar, etc.) we let
   * the standard PostgreSQL array mechanism handle them, because a user-defined
   * collection like {@code TYPE num_varray IS VARRAY(50) OF INTEGER} should NOT
   * override the standard {@code _int4} array type. Users should pass the
   * collection type name directly in those cases.
   *
   * @param elemOid the OID of the element type
   * @return the collection type OID, or {@link Oid#UNSPECIFIED} if none found
   */
  private int findCollectionTypeByElement(int elemOid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      boolean hasPkgSupport = hasPackageNamespaceSupport();
      PreparedStatement stmt = prepareFindCollectionByElementStmt(hasPkgSupport);
      stmt.setInt(1, elemOid);

      if (!((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        return Oid.UNSPECIFIED;
      }

      ResultSet rs = castNonNull(stmt.getResultSet());
      int collOid = Oid.UNSPECIFIED;
      if (rs.next()) {
        collOid = (int) rs.getLong(1);
        String typName = castNonNull(rs.getString(2));
        oidToPgName.put(collOid, typName);
        pgNameToOid.put(typName, collOid);
      }
      rs.close();
      return collOid;
    }
  }

  private PreparedStatement prepareFindCollectionByElementStmt(boolean hasPkgSupport)
      throws SQLException {
    PreparedStatement stmt = this.findCollectionByElementStmt;
    if (stmt == null) {
      // Only find collection types whose element is a composite type (typcategory='C').
      // J = VARRAY, K = Nested Table (TABLE OF), L = Associative Array (INDEX BY)
      String sql;
      if (hasPkgSupport) {
        sql = "SELECT ct.oid, "
            + "CASE WHEN p.nspname IS NOT NULL THEN "
            + "'\"' || replace(p.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(n.nspname, '\"', '\"\"') || '\".\"' "
            + "|| replace(ct.typname, '\"', '\"\"') || '\"' "
            + "ELSE ct.typname END "
            + "FROM pg_catalog.pg_type ct "
            + "JOIN pg_catalog.pg_type et ON ct.typelem = et.oid "
            + "JOIN pg_catalog.pg_namespace n ON ct.typnamespace = n.oid "
            + "LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid "
            + "WHERE ct.typelem = ? AND ct.typcategory IN ('J','K','L') "
            + "AND et.typcategory = 'C' "
            + "ORDER BY ct.oid LIMIT 1";
      } else {
        sql = "SELECT ct.oid, ct.typname FROM pg_catalog.pg_type ct"
            + " JOIN pg_catalog.pg_type et ON ct.typelem = et.oid"
            + " WHERE ct.typelem = ? AND ct.typcategory IN ('J','K','L')"
            + " AND et.typcategory = 'C'"
            + " ORDER BY ct.oid LIMIT 1";
      }
      this.findCollectionByElementStmt = stmt = conn.prepareStatement(sql);
    }
    return stmt;
  }

  /**
   * Return the oid of the array's base element if it's an array, if not return the provided oid.
   * This doesn't do any database lookups, so it's only useful for the originally provided type
   * mappings. This is fine for it's intended uses where we only have intimate knowledge of types
   * that are already known to the driver.
   *
   * @param oid input oid
   * @return oid of the array's base element or the provided oid (if not array)
   */
  protected int convertArrayToBaseOid(int oid) {
    try (ResourceLock ignore = lock.obtain()) {
      Integer i = pgArrayToPgType.get(oid);
      if (i == null) {
        return oid;
      }
      return i;
    }
  }

  public char getArrayDelimiter(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (oid == Oid.UNSPECIFIED) {
        return ',';
      }

      Character delim = arrayOidToDelimiter.get(oid);
      if (delim != null) {
        return delim;
      }

      PreparedStatement getArrayDelimiterStatement = prepareGetArrayDelimiterStatement();

      getArrayDelimiterStatement.setInt(1, oid);

      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) getArrayDelimiterStatement)
          .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      ResultSet rs = castNonNull(getArrayDelimiterStatement.getResultSet());
      if (!rs.next()) {
        // POLAR: For types without a typelem relationship (e.g. cross-package
        // TABLE OF RECORD types), default to comma delimiter instead of crashing.
        rs.close();
        delim = ',';
        arrayOidToDelimiter.put(oid, delim);
        return delim;
      }

      String s = castNonNull(rs.getString(1));
      delim = s.charAt(0);

      arrayOidToDelimiter.put(oid, delim);

      rs.close();

      return delim;
    }
  }

  private PreparedStatement prepareGetArrayDelimiterStatement() throws SQLException {
    PreparedStatement getArrayDelimiterStatement = this.getArrayDelimiterStatement;
    if (getArrayDelimiterStatement == null) {
      String sql;
      sql = "SELECT e.typdelim FROM pg_catalog.pg_type t, pg_catalog.pg_type e "
            + "WHERE t.oid = ? and t.typelem = e.oid";
      this.getArrayDelimiterStatement = getArrayDelimiterStatement = conn.prepareStatement(sql);
    }
    return getArrayDelimiterStatement;
  }

  public int getPGArrayElement(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (oid == Oid.UNSPECIFIED) {
        return Oid.UNSPECIFIED;
      }

      Integer pgType = pgArrayToPgType.get(oid);

      if (pgType != null) {
        return pgType;
      }

      boolean hasPkgSupport = hasPackageNamespaceSupport();
      PreparedStatement getArrayElementOidStatement =
          prepareGetArrayElementOidStatement(hasPkgSupport);

      getArrayElementOidStatement.setInt(1, oid);

      // Go through BaseStatement to avoid transaction start.
      if (!((BaseStatement) getArrayElementOidStatement)
          .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }

      ResultSet rs = castNonNull(getArrayElementOidStatement.getResultSet());
      if (!rs.next()) {
        rs.close();
        /* POLAR: The standard typelem JOIN returned no rows. This happens for
         * PolarDB TABLE OF types whose typelem=0. Try the reverse lookup:
         * find the element type whose typarray column points to this OID. */
        PreparedStatement fallbackStmt = prepareGetArrayElementByTyparrayStatement(hasPkgSupport);
        fallbackStmt.setInt(1, oid);
        if (!((BaseStatement) fallbackStmt)
            .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
          throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
        }
        rs = castNonNull(fallbackStmt.getResultSet());
        if (!rs.next()) {
          throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
        }
      }

      pgType = (int) rs.getLong(1);
      boolean onPath = rs.getBoolean(2);
      String schema = castNonNull(rs.getString(3), "schema");
      String name = castNonNull(rs.getString(4));
      String pkgSchema = hasPkgSupport ? rs.getString(5) : null;
      pgArrayToPgType.put(oid, pgType);
      if (pkgSchema != null) {
        cachePackageTypeName(pgType, pkgSchema, schema, name);
      } else {
        pgNameToOid.put(schema + "." + name, pgType);
        String fullName = "\"" + schema + "\".\"" + name + "\"";
        pgNameToOid.put(fullName, pgType);
        if (onPath && name.equals(name.toLowerCase(Locale.ROOT))) {
          oidToPgName.put(pgType, name);
          pgNameToOid.put(name, pgType);
        } else {
          oidToPgName.put(pgType, fullName);
        }
      }

      rs.close();

      return pgType;
    }
  }

  private PreparedStatement prepareGetArrayElementOidStatement(boolean hasPkgSupport)
      throws SQLException {
    PreparedStatement getArrayElementOidStatement = this.getArrayElementOidStatement;
    if (getArrayElementOidStatement == null) {
      String sql;
      if (hasPkgSupport) {
        sql = "SELECT e.oid, n.nspname = ANY(current_schemas(true)), n.nspname, e.typname, "
            + "p.nspname "
            + "FROM pg_catalog.pg_type t JOIN pg_catalog.pg_type e ON t.typelem = e.oid "
            + "JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid "
            + "LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid WHERE t.oid = ?";
      } else {
        sql = "SELECT e.oid, n.nspname = ANY(current_schemas(true)), n.nspname, e.typname "
            + "FROM pg_catalog.pg_type t JOIN pg_catalog.pg_type e ON t.typelem = e.oid "
            + "JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid WHERE t.oid = ?";
      }
      this.getArrayElementOidStatement = getArrayElementOidStatement = conn.prepareStatement(sql);
    }
    return getArrayElementOidStatement;
  }

  /**
   * POLAR: Fallback statement for {@link #getPGArrayElement(int)} when the
   * standard typelem lookup returns no rows (typelem=0). Reverses the
   * relationship: find the element type whose {@code typarray} points to
   * the given array-type OID.
   */
  private PreparedStatement prepareGetArrayElementByTyparrayStatement(boolean hasPkgSupport)
      throws SQLException {
    PreparedStatement stmt = this.getArrayElementByTyparrayStatement;
    if (stmt == null) {
      String sql;
      if (hasPkgSupport) {
        sql = "SELECT e.oid, n.nspname = ANY(current_schemas(true)), n.nspname, e.typname, "
            + "p.nspname "
            + "FROM pg_catalog.pg_type e "
            + "JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid "
            + "LEFT JOIN pg_catalog.pg_namespace p ON n.nsppkgns = p.oid "
            + "WHERE e.typarray = ?";
      } else {
        sql = "SELECT e.oid, n.nspname = ANY(current_schemas(true)), n.nspname, e.typname "
            + "FROM pg_catalog.pg_type e "
            + "JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid "
            + "WHERE e.typarray = ?";
      }
      this.getArrayElementByTyparrayStatement = stmt = conn.prepareStatement(sql);
    }
    return stmt;
  }

  public @Nullable Class<? extends PGobject> getPGobject(String type) {
    try (ResourceLock ignore = lock.obtain()) {
      return pgNameToPgObject.get(type);
    }
  }

  public String getJavaClass(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      String pgTypeName = getPGType(oid);
      if (pgTypeName == null) {
        // Technically speaking, we should not be here
        // null result probably means oid == UNSPECIFIED which has no clear way
        // to map to Java
        return "java.lang.String";
      }

      String result = pgNameToJavaClass.get(pgTypeName);
      if (result != null) {
        return result;
      }

      if (getSQLType(pgTypeName) == Types.ARRAY) {
        result = "java.sql.Array";
        pgNameToJavaClass.put(pgTypeName, result);
      }

      return result == null ? "java.lang.String" : result;
    }
  }

  public @Nullable String getTypeForAlias(@Nullable String alias) {
    if ( alias == null ) {
      return null;
    }
    String type = TYPE_ALIASES.get(alias);
    if (type != null) {
      return type;
    }
    type = TYPE_ALIASES.get(alias.toLowerCase(Locale.ROOT));
    if (type == null) {
      type = alias;
    }
    //populate for future use
    TYPE_ALIASES.put(alias, type);
    return type;
  }

  public int getPrecision(int oid, int typmod) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.INT2:
        return 5;

      case Oid.OID:
      case Oid.INT4:
        return 10;

      case Oid.INT8:
        return 19;

      case Oid.FLOAT4:
        // For float4 and float8, we can normally only get 6 and 15
        // significant digits out, but extra_float_digits may raise
        // that number by up to two digits.
        return 8;

      case Oid.FLOAT8:
        return 17;

      case Oid.NUMERIC:
        if (typmod == -1) {
          return 0;
        }
        return ((typmod - 4) & 0xFFFF0000) >> 16;

      case Oid.CHAR:
      case Oid.BOOL:
        return 1;

      case Oid.BPCHAR:
      case Oid.VARCHAR:
        if (typmod == -1) {
          return unknownLength;
        }
        return typmod - 4;

      // datetime types get the
      // "length in characters of the String representation"
      case Oid.DATE:
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.INTERVAL:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        return getDisplaySize(oid, typmod);

      case Oid.BIT:
        return typmod;

      case Oid.VARBIT:
        if (typmod == -1) {
          return unknownLength;
        }
        return typmod;

      case Oid.TEXT:
      case Oid.BYTEA:
      default:
        return unknownLength;
    }
  }

  public int getScale(int oid, int typmod) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.FLOAT4:
        return 8;
      case Oid.FLOAT8:
        return 17;
      case Oid.NUMERIC:
        if (typmod == -1) {
          return 0;
        }
        return (typmod - 4) & 0xFFFF;
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        if (typmod == -1) {
          return 6;
        }
        return typmod;
      case Oid.INTERVAL:
        if (typmod == -1) {
          return 6;
        }
        return typmod & 0xFFFF;
      default:
        return 0;
    }
  }

  public boolean isCaseSensitive(int oid) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.OID:
      case Oid.INT2:
      case Oid.INT4:
      case Oid.INT8:
      case Oid.FLOAT4:
      case Oid.FLOAT8:
      case Oid.NUMERIC:
      case Oid.BOOL:
      case Oid.BIT:
      case Oid.VARBIT:
      case Oid.DATE:
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
      case Oid.INTERVAL:
        return false;
      default:
        return true;
    }
  }

  public boolean isSigned(int oid) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.INT2:
      case Oid.INT4:
      case Oid.INT8:
      case Oid.FLOAT4:
      case Oid.FLOAT8:
      case Oid.NUMERIC:
        return true;
      default:
        return false;
    }
  }

  public int getDisplaySize(int oid, int typmod) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.INT2:
        return 6; // -32768 to +32767
      case Oid.INT4:
        return 11; // -2147483648 to +2147483647
      case Oid.OID:
        return 10; // 0 to 4294967295
      case Oid.INT8:
        return 20; // -9223372036854775808 to +9223372036854775807
      case Oid.FLOAT4:
        // varies based upon the extra_float_digits GUC.
        // These values are for the longest possible length.
        return 15; // sign + 9 digits + decimal point + e + sign + 2 digits
      case Oid.FLOAT8:
        return 25; // sign + 18 digits + decimal point + e + sign + 3 digits
      case Oid.CHAR:
        return 1;
      case Oid.BOOL:
        return 1;
      case Oid.DATE:
        return 13; // "4713-01-01 BC" to "01/01/4713 BC" - "31/12/32767"
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        // Calculate the number of decimal digits + the decimal point.
        int secondSize;
        switch (typmod) {
          case -1:
            secondSize = 6 + 1;
            break;
          case 0:
            secondSize = 0;
            break;
          case 1:
            // Bizarrely SELECT '0:0:0.1'::time(1); returns 2 digits.
            secondSize = 2 + 1;
            break;
          default:
            secondSize = typmod + 1;
            break;
        }

        // We assume the worst case scenario for all of these.
        // time = '00:00:00' = 8
        // date = '5874897-12-31' = 13 (although at large values second precision is lost)
        // date = '294276-11-20' = 12 --enable-integer-datetimes
        // zone = '+11:30' = 6;

        switch (oid) {
          case Oid.TIME:
            return 8 + secondSize;
          case Oid.TIMETZ:
            return 8 + secondSize + 6;
          case Oid.TIMESTAMP:
            return 13 + 1 + 8 + secondSize;
          case Oid.TIMESTAMPTZ:
            return 13 + 1 + 8 + secondSize + 6;
        }
      case Oid.INTERVAL:
        // SELECT LENGTH('-123456789 years 11 months 33 days 23 hours 10.123456 seconds'::interval);
        return 49;
      case Oid.VARCHAR:
      case Oid.BPCHAR:
        if (typmod == -1) {
          return unknownLength;
        }
        return typmod - 4;
      case Oid.NUMERIC:
        if (typmod == -1) {
          return 131089; // SELECT LENGTH(pow(10::numeric,131071)); 131071 = 2^17-1
        }
        int precision = (typmod - 4 >> 16) & 0xffff;
        int scale = (typmod - 4) & 0xffff;
        // sign + digits + decimal point (only if we have nonzero scale)
        return 1 + precision + (scale != 0 ? 1 : 0);
      case Oid.BIT:
        return typmod;
      case Oid.VARBIT:
        if (typmod == -1) {
          return unknownLength;
        }
        return typmod;
      case Oid.TEXT:
      case Oid.BYTEA:
        return unknownLength;
      default:
        return unknownLength;
    }
  }

  public int getMaximumPrecision(int oid) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.NUMERIC:
        return 1000;
      case Oid.TIME:
      case Oid.TIMETZ:
        // Technically this depends on the --enable-integer-datetimes
        // configure setting. It is 6 with integer and 10 with float.
        return 6;
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
      case Oid.INTERVAL:
        return 6;
      case Oid.BPCHAR:
      case Oid.VARCHAR:
        return 10485760;
      case Oid.BIT:
      case Oid.VARBIT:
        return 83886080;
      default:
        return 0;
    }
  }

  public boolean requiresQuoting(int oid) throws SQLException {
    int sqlType = getSQLType(oid);
    return requiresQuotingSqlType(sqlType);
  }

  /**
   * Returns true if particular sqlType requires quoting.
   * This method is used internally by the driver, so it might disappear without notice.
   *
   * @param sqlType sql type as in java.sql.Types
   * @return true if the type requires quoting
   * @throws SQLException if something goes wrong
   */
  public boolean requiresQuotingSqlType(int sqlType) throws SQLException {
    switch (sqlType) {
      case Types.BIGINT:
      case Types.DOUBLE:
      case Types.FLOAT:
      case Types.INTEGER:
      case Types.REAL:
      case Types.SMALLINT:
      case Types.TINYINT:
      case Types.NUMERIC:
      case Types.DECIMAL:
        return false;
    }
    return true;
  }

  @Override
  public int longOidToInt(long oid) throws SQLException {
    if ((oid & 0xFFFF_FFFF_0000_0000L) != 0) {
      throw new PSQLException(GT.tr("Value is not an OID: {0}", oid), PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }

    return (int) oid;
  }

  @Override
  public long intOidToLong(int oid) {
    return ((long) oid) & 0xFFFFFFFFL;
  }

  /* POLAR */
  public Integer getOidFromSqlType(Integer sqlType) {
    Integer oid = (Integer) sqlTypeToOid.get(sqlType);
    if (oid == null) {
      oid = new Integer(Oid.UNSPECIFIED);
    }
    return oid;
  }

  /**
   * POLAR: concrete JDBC-&gt;OID mapping for pre-typed OUT parameter slots.
   *
   * <p>Mirrors the regular bind mapping ({@code PgPreparedStatement.setNull}) so
   * behaviour stays consistent, with one deliberate difference: temporal types
   * map to their concrete OIDs instead of UNSPECIFIED, because an unknown-typed
   * PL/SQL OUT variable would coerce date-&gt;text via the session nls_date_format
   * (lossy for RR/YY formats). Note this must NOT delegate to
   * {@link #getOidFromSqlType(Integer)}: that is a metadata mapping whose table
   * aliases collapse e.g. Types.VARCHAR to Oid.NAME, which breaks procedure
   * resolution.</p>
   */
  @Override
  public int getOutParameterBindOid(int sqlType) {
    switch (sqlType) {
      case Types.TINYINT:
      case Types.SMALLINT:
        return Oid.INT2;
      case Types.INTEGER:
        return Oid.INT4;
      case Types.BIGINT:
        return Oid.INT8;
      case Types.REAL:
        return Oid.FLOAT4;
      case Types.DOUBLE:
      case Types.FLOAT:
        return Oid.FLOAT8;
      case Types.NUMERIC:
      case Types.DECIMAL:
        return Oid.NUMERIC;
      case Types.CHAR:
        return Oid.BPCHAR;
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return conn.getStringVarcharFlag() ? Oid.VARCHAR : 0;
      case Types.DATE:
        return conn.isMapDateToTimestamp() ? Oid.TIMESTAMP : Oid.DATE;
      case Types.TIMESTAMP:
        return Oid.TIMESTAMP;
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return Oid.TIMESTAMPTZ;
      case Types.TIME:
        return Oid.TIME;
      case Types.TIME_WITH_TIMEZONE:
        return Oid.TIMETZ;
      case Types.BOOLEAN:
      case Types.BIT:
        return Oid.BOOL;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return Oid.BYTEA;
      case Types.BLOB:
        return conn.getBlobAsBytea() ? Oid.BYTEA : Oid.OID;
      case Types.CLOB:
        return conn.getClobAsText()
            ? (conn.getStringVarcharFlag() ? Oid.VARCHAR : 0)
            : Oid.OID;
      case Types.REF_CURSOR:
        return Oid.REF_CURSOR;
      default:
        // No concrete OID (e.g. Types.OTHER): keep the legacy unknown bind so the
        // server infers the variable type.
        return 0;
    }
  }

  /**
   * POLAR: Look up {@code pg_type.typcategory} for the given OID.
   *
   * <p>Result is cached. Returns {@code '\0'} when the OID is unknown or the
   * lookup yields no row (also cached as a negative result).
   *
   * <p>Relevant PolarDB-specific categories:
   * <ul>
   *   <li>{@code 'J'} - VARRAY (Oracle-compatible variable-length array)</li>
   *   <li>{@code 'K'} - Nested Table / TABLE OF</li>
   *   <li>{@code 'L'} - Associative Array / INDEX BY (key-value map)</li>
   *   <li>{@code 'A'} - Standard PostgreSQL array</li>
   *   <li>{@code 'C'} - Composite/record</li>
   * </ul>
   */
  public char getTypeCategory(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return '\0';
    }
    try (ResourceLock ignore = lock.obtain()) {
      Character cached = oidToTypCategory.get(oid);
      if (cached != null) {
        return cached;
      }
      PreparedStatement stmt = prepareGetTypeCategoryStatement();
      stmt.setInt(1, oid);
      char cat = '\0';
      if (((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        ResultSet rs = castNonNull(stmt.getResultSet());
        try {
          if (rs.next()) {
            String s = rs.getString(1);
            if (s != null && !s.isEmpty()) {
              cat = s.charAt(0);
            }
          }
        } finally {
          rs.close();
        }
      }
      oidToTypCategory.put(oid, cat);
      return cat;
    }
  }

  private PreparedStatement prepareGetTypeCategoryStatement() throws SQLException {
    PreparedStatement stmt = this.getTypeCategoryStatement;
    if (stmt == null) {
      String sql = "SELECT typcategory FROM pg_catalog.pg_type WHERE oid = ?";
      this.getTypeCategoryStatement = stmt = conn.prepareStatement(sql);
    }
    return stmt;
  }

  /**
   * POLAR: Returns {@code true} if the given OID names a PolarDB associative
   * array (PL/SQL {@code INDEX BY} table, {@code pg_type.typcategory='L'}).
   *
   * <p>The server's input function for these types does NOT accept the
   * standard PostgreSQL array literal {@code {"(...)","(...)"}}; it expects
   * the {@code (idx => "value", idx => "value", ...)} format. Callers must
   * format the IN parameter accordingly before binding it.
   */
  public boolean isAssociativeArrayType(int oid) throws SQLException {
    return getTypeCategory(oid) == 'L';
  }
}
