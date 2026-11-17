/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.PGCompositeField;
import com.aliyun.polardb2.PGConnection;
import com.aliyun.polardb2.core.TypeInfo;
import com.aliyun.polardb2.jdbc.PgConnection;
import com.aliyun.polardb2.test.TestUtil;
import com.aliyun.polardb2.util.PSQLException;
import com.aliyun.polardb2.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression tests for resolving 3-part Oracle package type names
 * ({@code SCHEMA.PACKAGE.TYPE}, e.g. {@code CAS.PKG_CRR.TBL_CRR_CALLBACK_RESULT})
 * in {@link Connection#createArrayOf}, {@link PGConnection#getCompositeTypeFields},
 * {@link CallableStatement#registerOutParameter}, and {@link TypeInfo}.
 */
public class ThreePartPackageTypeTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  private final String suffix =
      Long.toHexString(System.nanoTime()) + "_" + SEQ.incrementAndGet();
  private final String schemaA = "tpt_cas_" + suffix;
  private final String schemaB = "tpt_other_" + suffix;
  private final String pkgSynName = "syn_pkg_crr_" + suffix;

  private Connection conn;
  private PGConnection pgConn;
  private boolean synonymCreated;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);
    pgConn = conn.unwrap(PGConnection.class);

    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP SCHEMA IF EXISTS " + schemaB + " CASCADE");
      stmt.execute("DROP SCHEMA IF EXISTS " + schemaA + " CASCADE");
      stmt.execute("CREATE SCHEMA " + schemaA);
      stmt.execute("CREATE SCHEMA " + schemaB);

      stmt.execute(
          "CREATE OR REPLACE PACKAGE " + schemaA + ".pkg_crr IS\n"
          + "  TYPE REC_CRR_CALLBACK_RESULT IS RECORD (\n"
          + "    policyKey VARCHAR2(50),\n"
          + "    isInExemptionList VARCHAR2(10),\n"
          + "    hasStaleDatedChequeInLawson VARCHAR2(10),\n"
          + "    hasStaleDatedChequeInETreasury VARCHAR2(10),\n"
          + "    hasFinancialTrxnWithinRetentionPeriodInLawson VARCHAR2(10),\n"
          + "    hasFinancialTrxnWithinRetentionPeriodInETreasury VARCHAR2(10)\n"
          + "  );\n"
          + "  TYPE REC_PLAIN_NO_COLL IS RECORD (\n"
          + "    code VARCHAR2(20),\n"
          + "    val  VARCHAR2(20)\n"
          + "  );\n"
          + "  TYPE TBL_CRR_CALLBACK_RESULT IS TABLE OF REC_CRR_CALLBACK_RESULT;\n"
          + "  TYPE TBL_CRR_CALLBACK_RESULT_IDX IS TABLE OF REC_CRR_CALLBACK_RESULT\n"
          + "    INDEX BY BINARY_INTEGER;\n"
          + "  TYPE TBL_CRR_VARRAY IS VARRAY(100) OF REC_CRR_CALLBACK_RESULT;\n"
          + "  PROCEDURE upd_crr_callback_result(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_CALLBACK_RESULT,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  );\n"
          + "  PROCEDURE upd_crr_callback_idx(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_CALLBACK_RESULT_IDX,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  );\n"
          + "  PROCEDURE upd_crr_callback_varray(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_VARRAY,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  );\n"
          + "  PROCEDURE get_crr_out(\n"
          + "    p_result OUT TBL_CRR_CALLBACK_RESULT,\n"
          + "    p_idx    OUT TBL_CRR_CALLBACK_RESULT_IDX\n"
          + "  );\n"
          + "  PROCEDURE accept_single_rec(\n"
          + "    p_rec IN REC_CRR_CALLBACK_RESULT,\n"
          + "    p_out OUT VARCHAR2\n"
          + "  );\n"
          + "END pkg_crr;");

      stmt.execute(
          "CREATE OR REPLACE PACKAGE BODY " + schemaA + ".pkg_crr IS\n"
          + "  PROCEDURE upd_crr_callback_result(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_CALLBACK_RESULT,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  ) IS\n"
          + "  BEGIN\n"
          + "    p_out_msg := 'NESTED_OK:' || TO_CHAR(p_batch_date, 'YYYY-MM-DD')\n"
          + "        || ':' || p_request_id || ':' || p_result.COUNT\n"
          + "        || ':' || p_result(1).policyKey;\n"
          + "  END;\n"
          + "  PROCEDURE upd_crr_callback_idx(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_CALLBACK_RESULT_IDX,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  ) IS\n"
          + "  BEGIN\n"
          + "    p_out_msg := 'IDX_OK:' || TO_CHAR(p_batch_date, 'YYYY-MM-DD')\n"
          + "        || ':' || p_request_id || ':' || p_result.COUNT\n"
          + "        || ':' || p_result(1).policyKey;\n"
          + "  END;\n"
          + "  PROCEDURE upd_crr_callback_varray(\n"
          + "    p_batch_date IN DATE,\n"
          + "    p_request_id IN VARCHAR2,\n"
          + "    p_result     IN TBL_CRR_VARRAY,\n"
          + "    p_out_msg    OUT VARCHAR2\n"
          + "  ) IS\n"
          + "  BEGIN\n"
          + "    p_out_msg := 'VARRAY_OK:' || TO_CHAR(p_batch_date, 'YYYY-MM-DD')\n"
          + "        || ':' || p_request_id || ':' || p_result.COUNT\n"
          + "        || ':' || p_result(1).policyKey;\n"
          + "  END;\n"
          + "  PROCEDURE get_crr_out(\n"
          + "    p_result OUT TBL_CRR_CALLBACK_RESULT,\n"
          + "    p_idx    OUT TBL_CRR_CALLBACK_RESULT_IDX\n"
          + "  ) IS\n"
          + "  BEGIN\n"
          + "    p_result := TBL_CRR_CALLBACK_RESULT();\n"
          + "    p_result.EXTEND;\n"
          + "    p_result(1).policyKey := 'IFP-POL-109947987';\n"
          + "    p_result(1).isInExemptionList := 'N';\n"
          + "    p_idx(1).policyKey := 'IFP-POL-109947987';\n"
          + "    p_idx(1).isInExemptionList := 'N';\n"
          + "  END;\n"
          + "  PROCEDURE accept_single_rec(\n"
          + "    p_rec IN REC_CRR_CALLBACK_RESULT,\n"
          + "    p_out OUT VARCHAR2\n"
          + "  ) IS\n"
          + "  BEGIN\n"
          + "    p_out := 'REC_OK:' || p_rec.policyKey;\n"
          + "  END;\n"
          + "END pkg_crr;");

      // Second schema with same package name & higher OID to test disambiguation
      stmt.execute(
          "CREATE OR REPLACE PACKAGE " + schemaB + ".pkg_crr IS\n"
          + "  TYPE REC_CRR_CALLBACK_RESULT IS RECORD (dummy VARCHAR2(10));\n"
          + "  TYPE TBL_CRR_CALLBACK_RESULT IS TABLE OF REC_CRR_CALLBACK_RESULT;\n"
          + "END pkg_crr;");

      // Optional synonym pointing to schemaA.pkg_crr (created in public so tearDown drops it cleanly)
      try {
        stmt.execute(
            "CREATE OR REPLACE SYNONYM public." + pkgSynName + " FOR " + schemaA + ".pkg_crr");
        synonymCreated = true;
      } catch (SQLException ignored) {
        synonymCreated = false;
      }

      // Neither schemaA nor schemaB is in search_path by default
      stmt.execute("SET search_path TO public");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (conn == null || conn.isClosed()) {
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      if (synonymCreated) {
        try {
          stmt.execute("DROP SYNONYM public." + pkgSynName);
        } catch (SQLException ignored) {
          // ignore
        }
      }
      stmt.execute("DROP SCHEMA IF EXISTS " + schemaB + " CASCADE");
      stmt.execute("DROP SCHEMA IF EXISTS " + schemaA + " CASCADE");
    } finally {
      conn.close();
    }
  }

  private Struct[] sampleRows() throws SQLException {
    String recName = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.REC_CRR_CALLBACK_RESULT";
    Struct s1 = conn.createStruct(
        recName, new Object[]{"IFP-POL-109947987", "N", "N", "N", "N", "N"});
    Struct s2 = conn.createStruct(
        recName, new Object[]{"IFP-POL-110264042", "N", "N", "N", "N", "N"});
    return new Struct[]{s1, s2};
  }

  @Test
  public void testThreePartNestedTableCreateArrayOfAndCall() throws SQLException {
    String tblName = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.TBL_CRR_CALLBACK_RESULT";
    Array arr = conn.createArrayOf(tblName, sampleRows());

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "1");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("NESTED_OK:2026-11-13:1:2:IFP-POL-109947987", cs.getString(4));
    }

    // Object[][] overload used by OracleArrayParameter-style callers
    Object[][] obj2d = new Object[][]{
        {"IFP-POL-109947987", "N", "N", "N", "N", "N"},
        {"IFP-POL-110264042", "N", "N", "N", "N", "N"}
    };
    Array arr2d = pgConn.createArrayOf(tblName, obj2d);
    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "2");
      cs.setArray(3, arr2d);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("NESTED_OK:2026-11-13:2:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testThreePartAssociativeArrayCreateArrayOfAndCall() throws SQLException {
    String idxName = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.TBL_CRR_CALLBACK_RESULT_IDX";
    Array arr = conn.createArrayOf(idxName, sampleRows());

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_idx(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "1");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("IDX_OK:2026-11-13:1:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testThreePartVarrayCreateArrayOfAndCall() throws SQLException {
    String varrayName = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.TBL_CRR_VARRAY";
    Array arr = conn.createArrayOf(varrayName, sampleRows());

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_varray(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "1");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("VARRAY_OK:2026-11-13:1:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testThreePartQuotedUppercaseAndMixedCase() throws SQLException {
    String quotedUpper =
        "\"" + schemaA.toUpperCase(Locale.ROOT) + "\".\"PKG_CRR\".\"TBL_CRR_CALLBACK_RESULT\"";
    Array arr = conn.createArrayOf(quotedUpper, sampleRows());

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "3");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("NESTED_OK:2026-11-13:3:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testThreePartElementRecordNameResolvesCollection() throws SQLException {
    // SimpleJdbcCall passes the element RECORD name rather than the TABLE OF name
    String recName = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.REC_CRR_CALLBACK_RESULT";
    Array arr = conn.createArrayOf(recName, sampleRows());

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "4");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("NESTED_OK:2026-11-13:4:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testThreePartGetCompositeTypeFieldsAndStructBind() throws SQLException {
    String recNameA = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.REC_CRR_CALLBACK_RESULT";
    List<PGCompositeField> fieldsA = pgConn.getCompositeTypeFields(recNameA);
    assertNotNull("3-part getCompositeTypeFields should resolve", fieldsA);
    assertEquals(6, fieldsA.size());
    assertEquals("policykey", fieldsA.get(0).getFieldName());
    assertEquals("isinexemptionlist", fieldsA.get(1).getFieldName());

    // Disambiguate against schemaB.pkg_crr (higher OID, 1 field)
    String recNameB = schemaB.toUpperCase(Locale.ROOT) + ".PKG_CRR.REC_CRR_CALLBACK_RESULT";
    List<PGCompositeField> fieldsB = pgConn.getCompositeTypeFields(recNameB);
    assertNotNull(fieldsB);
    assertEquals(1, fieldsB.size());
    assertEquals("dummy", fieldsB.get(0).getFieldName());

    Struct s = conn.createStruct(
        recNameA, new Object[]{"IFP-POL-109947987", "N", "N", "N", "N", "N"});
    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.accept_single_rec(?, ?) }")) {
      cs.setObject(1, s, Types.STRUCT);
      cs.registerOutParameter(2, Types.VARCHAR);
      cs.execute();
      assertEquals("REC_OK:IFP-POL-109947987", cs.getString(2));
    }
  }

  @Test
  public void testTwoPartPackageRespectsSearchPathOverHigherOid() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SET search_path TO " + schemaA + ", public");
    }
    try {
      List<PGCompositeField> fields =
          pgConn.getCompositeTypeFields("PKG_CRR.REC_CRR_CALLBACK_RESULT");
      assertNotNull(fields);
      assertEquals("search_path should prioritize schemaA.pkg_crr over schemaB.pkg_crr",
          6, fields.size());
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("SET search_path TO public");
      }
    }
  }

  @Test
  public void testThreePartOutParamsAndReverseLookup() throws SQLException {
    String tblNested = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.TBL_CRR_CALLBACK_RESULT";
    String tblIdx = schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.TBL_CRR_CALLBACK_RESULT_IDX";

    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.get_crr_out(?, ?) }")) {
      cs.registerOutParameter(1, Types.ARRAY, tblNested);
      cs.registerOutParameter(2, Types.ARRAY, tblIdx);
      cs.execute();

      Array outNested = cs.getArray(1);
      Array outIdx = cs.getArray(2);
      assertNotNull(outNested);
      assertNotNull(outIdx);

      String expectedElem = "\"" + schemaA + "\".\"pkg_crr\".\"rec_crr_callback_result\"";
      assertEquals(expectedElem, outNested.getBaseTypeName());
      assertEquals(expectedElem, outIdx.getBaseTypeName());

      Object[] nestedElems = (Object[]) outNested.getArray();
      Object[] idxElems = (Object[]) outIdx.getArray();
      assertEquals(1, nestedElems.length);
      assertEquals(1, idxElems.length);
      assertEquals("IFP-POL-109947987", ((Struct) nestedElems[0]).getAttributes()[0]);
      assertEquals("IFP-POL-109947987", ((Struct) idxElems[0]).getAttributes()[0]);
    }

    // Reverse lookup via getPGType(oid) and [] array suffix on record without collection
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
    int nestedOid = ti.getPGType(tblNested.toLowerCase(Locale.ROOT));
    assertTrue(nestedOid > 0);
    assertEquals("\"" + schemaA + "\".\"pkg_crr\".\"tbl_crr_callback_result\"",
        ti.getPGType(nestedOid));

    int recArrOid = ti.getPGArrayType(schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.REC_PLAIN_NO_COLL");
    assertTrue("Record without explicit collection should resolve its _record[] OID", recArrOid > 0);
  }

  @Test
  public void testPackageSynonymResolution() throws SQLException {
    if (!synonymCreated) {
      return;
    }
    Array arr = conn.createArrayOf(
        pkgSynName.toUpperCase(Locale.ROOT) + ".TBL_CRR_CALLBACK_RESULT", sampleRows());
    try (CallableStatement cs = conn.prepareCall(
        "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
      cs.setDate(1, Date.valueOf("2026-11-13"));
      cs.setString(2, "5");
      cs.setArray(3, arr);
      cs.registerOutParameter(4, Types.VARCHAR);
      cs.execute();
      assertEquals("NESTED_OK:2026-11-13:5:2:IFP-POL-109947987", cs.getString(4));
    }
  }

  @Test
  public void testPackageSynonymNotHijackedByTempAllSynonyms() throws Exception {
    if (!synonymCreated) {
      return;
    }
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    try (Connection freshConn = TestUtil.openDB(props)) {
      freshConn.setAutoCommit(false);
      try (Statement stmt = freshConn.createStatement()) {
        // Shadow unqualified "all_synonyms" with a temp table lacking expected columns;
        // driver must probe and query sys.all_synonyms directly without aborting the tx.
        stmt.execute("CREATE TEMP TABLE all_synonyms (dummy integer)");
      }
      Array arr = freshConn.createArrayOf(
          pkgSynName.toUpperCase(Locale.ROOT) + ".TBL_CRR_CALLBACK_RESULT", sampleRows());
      assertNotNull(arr);
      try (CallableStatement cs = freshConn.prepareCall(
          "{ call " + schemaA + ".pkg_crr.upd_crr_callback_result(?, ?, ?, ?) }")) {
        cs.setDate(1, Date.valueOf("2026-11-13"));
        cs.setString(2, "6");
        cs.setArray(3, arr);
        cs.registerOutParameter(4, Types.VARCHAR);
        cs.execute();
        assertEquals("NESTED_OK:2026-11-13:6:2:IFP-POL-109947987", cs.getString(4));
      }
      freshConn.rollback();
    }
  }

  @Test
  public void testUnknownAndMalformedNamesThrowInvalidName() throws SQLException {
    String[] badNames = {
        schemaA.toUpperCase(Locale.ROOT) + ".PKG_CRR.NO_SUCH_TYPE",
        "A.B.C.D",
        schemaA.toUpperCase(Locale.ROOT) + "..TBL_CRR_CALLBACK_RESULT"
    };
    for (String bad : badNames) {
      try {
        conn.createArrayOf(bad, new Object[]{});
        fail("Expected PSQLException for " + bad);
      } catch (PSQLException expected) {
        assertEquals(PSQLState.INVALID_NAME.getState(), expected.getSQLState());
      }
    }
    // Verify connection remains usable (not in aborted transaction state)
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }
}
