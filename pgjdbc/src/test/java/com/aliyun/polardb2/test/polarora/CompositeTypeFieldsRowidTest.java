/*
 * Test: PGConnection.getCompositeTypeFields against TABLE row types, in the
 * presence of PolarDB's built-in hidden column polar_sys_rowid_attr.
 *
 * Background: every PolarDB-O table carries a hidden system column
 * polar_sys_rowid_attr (positive attnum, not dropped) that the server EXCLUDES
 * from composite input/output: a table row type serializes only the user
 * columns, and a record literal with an extra field is rejected with
 * "malformed record literal: Too many columns".
 *
 * The driver's composite metadata queries (TypeInfoCache) read
 * pg_attribute with "attnum > 0 AND NOT attisdropped", so without filtering
 * the hidden column leaks into:
 *   - getCompositeTypeFields(table): frameworks would map a Java bean field
 *     per returned field and then fail / send one value too many to the server;
 *   - getCompositeFieldTypeOids(row type): Struct.getAttributes() would
 *     type-restore fields AFTER the hidden column with a shifted (wrong) OID,
 *     silently corrupting the restored Java types.
 *
 * Standalone composite types (CREATE TYPE ... AS OBJECT) never receive the
 * hidden column, so the primary use case must stay unaffected (regression).
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aliyun.polardb2.PGCompositeField;
import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Verifies that the hidden polar_sys_rowid_attr column of PolarDB-O tables
 * never leaks into the composite type field metadata and never breaks the
 * framework flow (metadata-driven reordering + createStruct) nor the
 * Struct.getAttributes() type restoration for table row types.
 */
public class CompositeTypeFieldsRowidTest {

  // Unique names: test cases run in parallel, sharing fixed names would race.
  private final String suffix = Long.toHexString(System.nanoTime());
  private final String tableName = "cmpfld_rowtab_" + suffix;
  private final String funcName = "cmpfld_rowfn_" + suffix;
  private final String objTypeName = "cmpfld_rowobj_" + suffix;

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    conn = TestUtil.openDB(props);

    try (Statement stmt = conn.createStatement()) {
      dropQuietly(stmt);

      // Two columns first; the hidden rowid column takes the next attnum. The
      // ALTER below then appends "extra" AFTER it, so the hidden column sits in
      // the MIDDLE of pg_attribute order - the worst case for OID misalignment.
      stmt.execute("CREATE TABLE " + tableName
          + "(id NUMBER, name VARCHAR2(50))");
      stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN extra NUMBER");
      stmt.execute("INSERT INTO " + tableName + " VALUES (1, 'abc', 12345.678)");

      // Server-side reference for the createStruct roundtrip: accepts the
      // table row type and echoes the user columns (never the hidden one).
      stmt.execute("CREATE OR REPLACE FUNCTION " + funcName
          + "(t " + tableName + "%ROWTYPE) RETURN text IS"
          + " BEGIN RETURN 'id=' || t.id || ' extra=' || t.extra; END;");
    }
  }

  @After
  public void tearDown() throws Exception {
    try (Statement stmt = conn.createStatement()) {
      dropQuietly(stmt);
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  private void dropQuietly(Statement stmt) {
    try {
      stmt.execute("DROP FUNCTION IF EXISTS " + funcName);
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TABLE IF EXISTS " + tableName);
    } catch (SQLException ignore) {
      // ignore
    }
    try {
      stmt.execute("DROP TYPE IF EXISTS " + objTypeName);
    } catch (SQLException ignore) {
      // ignore
    }
  }

  /**
   * Regression guard for the primary use case: standalone composite types
   * (CREATE TYPE ... AS OBJECT) never receive the hidden rowid column, so
   * their field metadata must stay exactly as declared.
   */
  @Test
  public void testAsObjectTypeStaysClean() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TYPE " + objTypeName + " AS OBJECT ("
          + "a NUMBER, "
          + "b VARCHAR2(20))");
    }
    try {
      PGConnection pgConn = conn.unwrap(PGConnection.class);
      List<PGCompositeField> fields = pgConn.getCompositeTypeFields(objTypeName);

      assertNotNull("AS OBJECT type must resolve to its fields", fields);
      assertEquals(2, fields.size());
      assertEquals("a", fields.get(0).getFieldName());
      assertEquals("b", fields.get(1).getFieldName());
      for (PGCompositeField field : fields) {
        assertFalse("hidden rowid column must never appear in AS OBJECT metadata",
            "polar_sys_rowid_attr".equalsIgnoreCase(field.getFieldName()));
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS " + objTypeName);
      } catch (SQLException ignore) {
        // ignore
      }
    }
  }

  /**
   * The metadata of a TABLE row type must describe exactly the user columns the
   * server serializes - i.e. the hidden rowid column must not leak - and field
   * numbers must keep their pg_attribute.attnum (skipping the hidden column).
   */
  @Test
  public void testTableRowTypeFieldsExcludeHiddenRowidColumn() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    List<PGCompositeField> fields = pgConn.getCompositeTypeFields(tableName);

    assertNotNull("table row type must resolve to its fields", fields);
    assertEquals("field count must match the server's composite layout (user columns only)",
        3, fields.size());
    assertEquals("id", fields.get(0).getFieldName());
    assertEquals("name", fields.get(1).getFieldName());
    assertEquals("extra", fields.get(2).getFieldName());
    assertEquals(1, fields.get(0).getFieldNumber());
    assertEquals(2, fields.get(1).getFieldNumber());
    // attnum 3 is the hidden rowid column; "extra" keeps attnum 4.
    assertEquals(4, fields.get(2).getFieldNumber());
    for (PGCompositeField field : fields) {
      assertFalse("hidden rowid column must never leak into composite metadata",
          "polar_sys_rowid_attr".equalsIgnoreCase(field.getFieldName()));
    }
  }

  /**
   * The framework scenario on a TABLE row type: the Java bean has one property
   * per user column; the driver metadata must let the framework build a value
   * array that the server accepts. If the hidden column leaked into the
   * metadata, the bean lookup would fail here, and even a forced extra value
   * would be rejected by the server with "malformed record literal:
   * Too many columns" (the server's composite input has user columns only).
   */
  @Test
  public void testFrameworkFlowOnTableRowTypeRoundTrips() throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);

    // Java bean side: values keyed by Java field name.
    Map<String, Object> bean = new HashMap<String, Object>();
    bean.put("id", new BigDecimal("1"));
    bean.put("name", "abc");
    bean.put("extra", new BigDecimal("12345.678"));

    // Framework logic: DB field order from the driver + Java field names -> ordered array.
    List<PGCompositeField> dbFields = pgConn.getCompositeTypeFields(tableName);
    assertNotNull(dbFields);
    Object[] reordered = new Object[dbFields.size()];
    for (int i = 0; i < dbFields.size(); i++) {
      assertTrue("Java bean must contain field " + dbFields.get(i).getFieldName(),
          bean.containsKey(dbFields.get(i).getFieldName()));
      reordered[i] = bean.get(dbFields.get(i).getFieldName());
    }

    // Write through the standard positional API, then let the server consume
    // the value: proves the produced record literal matches the row type.
    Struct struct = conn.createStruct(tableName, reordered);
    CallableStatement cs = conn.prepareCall("{ ? = call " + funcName + "(?) }");
    try {
      cs.registerOutParameter(1, Types.VARCHAR);
      cs.setObject(2, struct);
      cs.execute();
      assertEquals("id=1 extra=12345.678", cs.getString(1));
    } finally {
      cs.close();
    }
  }

  /**
   * Reading a TABLE row type back (array of rows): the server wire format has
   * the user columns only, and Struct.getAttributes() must type-restore every
   * field with its OWN type OID. If the hidden column shifted the OID list,
   * "extra" would be restored with the rowid column's int8 OID and silently
   * degrade to a String instead of BigDecimal.
   */
  @Test
  public void testTableRowTypeArrayReadRestoresFieldTypes() throws SQLException {
    Statement stmt = conn.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("SELECT array_agg(r) FROM " + tableName + " r");
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      assertNotNull(array);
      Object[] rows = (Object[]) array.getArray();
      assertEquals(1, rows.length);
      Struct row = (Struct) rows[0];
      Object[] attrs = row.getAttributes();

      assertEquals("wire format carries the user columns only", 3, attrs.length);
      assertTrue("id (NUMBER) must restore to BigDecimal, was "
          + (attrs[0] == null ? "null" : attrs[0].getClass().getName()),
          attrs[0] instanceof BigDecimal);
      assertEquals("abc", attrs[1]);
      assertTrue("extra (NUMBER) must restore to BigDecimal - a hidden rowid column"
              + " in the middle must not shift the field type OIDs, was "
              + (attrs[2] == null ? "null" : attrs[2].getClass().getName()),
          attrs[2] instanceof BigDecimal);
      assertEquals(0, new BigDecimal("12345.678").compareTo((BigDecimal) attrs[2]));
    } finally {
      stmt.close();
    }
  }
}
